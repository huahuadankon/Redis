package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {

        //解决缓存穿透
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById,
                CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 互斥锁解决缓存击穿
        // Shop shop = cacheClient
        //         .queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //基于逻辑过期时间解决缓存击穿
        /*Shop shop1 = cacheClient.queryWithLogicExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById,
                CACHE_SHOP_TTL, TimeUnit.MINUTES);*/
        if(shop == null){
             return Result.fail("店铺不存在");
         }
         return Result.ok(shop);
    }

    /*//利用逻辑过期时间解决缓存击穿问题
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    private Shop queryWithLogicExpire(Long id) {
        String key = CACHE_SHOP_KEY+id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(shopJson)){
            return null;
        }
        //不为空，查询逻辑过期时间是否有效
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Object data = redisData.getData();
        Shop shop = JSONUtil.toBean((JSONObject) data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期，直接返回
            return shop;
        }

        //过期，则尝试获得锁，开启线程去更新数据
        boolean isLock = tryLock(LOCK_KEY);
        //获取锁成功
        if(isLock){
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                //重建缓存
                try {
                    this.saveShop2Redis(id,LOCK_TTL);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(LOCK_KEY);
                }
            });
        }

        //返回商铺信息，无论过期与否
        return shop;
    }


    public void saveShop2Redis(Long id,Long expireSeconds) {
        Shop shop = getById(id);
        RedisData redisData = new RedisData();
        redisData.setData(JSONUtil.toJsonStr(shop));
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY, JSONUtil.toJsonStr(shop));
    }

    private Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY+id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //不为空，直接查缓存
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if("".equals(shopJson)){
            //返回错误信息
            return null;
        }

        //实现重构缓存
        //先尝试获取锁
        String lockKey = LOCK_KEY+id;
        Shop shopById = null;
        try {

            boolean isLock = tryLock(lockKey);
            if(!isLock){
                //获取失败，休眠一会，再重新尝试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //为空，先查数据库，再写入缓存，为了解决缓存穿透，为空是写入空值
            shopById = getById(id);
            if(shopById == null){
                //将空字符串写进redis中
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shopById),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放锁
            unlock(lockKey);
        }

        return shopById;

    }


    //解决缓存穿透的代码
    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY+id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //不为空，直接查缓存
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if("".equals(shopJson)){
            return null;
        }

        //为空，先查数据库，再写入缓存，为了解决缓存穿透，为空是写入空值
        Shop shopById = getById(id);
        if(shopById == null){
            //将空字符串写进redis中
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shopById),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shopById;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1",  LOCK_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }*/





    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result update(Shop shop) {
        if(shop.getId() == null){
            return Result.fail("店铺ID不能为空");
        }
        //更新数据库，先后关系很重要
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+shop.getId());

        return Result.ok();
    }
}
