package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;


    @Resource
    private RedisIdWorker redisIdWorker;
    @Override
    public Result seckillVoucher(Long voucherId) {

        // 1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀尚未开始！");
        }
        // 3.判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀已经结束！");
        }
        // 4.判断库存是否充足
        if (voucher.getStock() < 1) {
            // 库存不足
            return Result.fail("库存不足！");
        }
        Long userId = UserHolder.getUser().getId();
        synchronized (userId.toString().intern()) {//通过 toString().intern() 将 Long 转成唯一的字符串实例，只是toString，仍然会new
            //将创建订单交给代理对象执行，才会触发Spring的事务管理，因为Spring的事务管理是通过代理对象完成的
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
    }
    //将锁加在方法内部可能导致事务未提交时锁已经释放，这种情况在高并发环境下会带来数据一致性问题。

    /**
     * 假设有两个线程 A 和 B 同时执行 seckillVoucher() 方法。流程如下：
     * 线程 A 获得锁，进入 seckillVoucher() 方法，执行到 createVoucherOrder() 时触发事务。
     * 线程 A 完成库存扣减和订单生成，但事务还没有提交。
     * 锁在方法内部，synchronized 代码块结束时释放了锁。
     * 线程 B 立即获得锁，开始执行库存检查。
     * 此时，由于线程 A 的事务还未提交，线程 B 看到的库存仍是扣减前的状态，导致库存超卖。
     */



    @Transactional
    public Result createVoucherOrder(Long voucherId) {//不适合将锁直接加在方法上，锁粗度太大，这样每个线程都会被锁住
        // 5.一人一单逻辑
        // 5.1.用户id
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 5.2.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            return Result.fail("用户已经购买过一次！");
        }

        //5，扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock= stock -1")
                .gt("stock",0)//乐观锁（业务层面）
                .eq("voucher_id", voucherId).update();
        if (!success) {
            //扣减库存
            return Result.fail("库存不足！");
        }


        //6.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 6.1.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        // 6.3.代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        return Result.ok(orderId);

    }
}
