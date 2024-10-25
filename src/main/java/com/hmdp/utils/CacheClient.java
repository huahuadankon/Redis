package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;


/**
 * @author liuyichen
 * @version 1.0
 */
@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;



    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    // 将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
    public void set(String key, Object object,Long Time, TimeUnit TimeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(object), Time, TimeUnit);

    }
    //将任意Java对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓存击穿问题
    public void setWithLogicExpire(String key, Object object,Long expire, TimeUnit TimeUnit) {
        RedisData redisData = new RedisData();
        redisData.setData(object);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(TimeUnit.toSeconds(expire)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
    //根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> clazz,Function<ID,R> dbFallBack,
                                         Long time, TimeUnit unit) {
        String key = keyPrefix+id;
        String Json = stringRedisTemplate.opsForValue().get(key);
        //不为空，直接查缓存
        if(StrUtil.isNotBlank(Json)){
            R r = JSONUtil.toBean(Json, clazz);
            return r;
        }
        if("".equals(Json)){
            return null;
        }

        //为空，先查数据库，再写入缓存，为了解决缓存穿透，为空是写入空值
        R apply = dbFallBack.apply(id);
        if(apply == null){
            //将空字符串写进redis中
            this.set(key,"",time,TimeUnit.MINUTES);
            return null;
        }
        this.set(key,apply,time,unit);
        return apply;
    }





    //根据指定的key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题将逻辑进行封装
     public <ID,R> R queryWithLogicExpire(String keyPrefix, ID id, Class<R> clazz,Function<ID,R> dbFallBack,
                                          Long time, TimeUnit unit) {
        String key = keyPrefix+id;
        String Json = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(Json)){
            return null;
        }
        //不为空，查询逻辑过期时间是否有效
        RedisData redisData = JSONUtil.toBean(Json, RedisData.class);
        Object data = redisData.getData();
         R r = JSONUtil.toBean((JSONObject) data, clazz);
         LocalDateTime expireTime = redisData.getExpireTime();

        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期，直接返回
            return r;
        }


        String lockKey = LOCK_SHOP_KEY+id;
        //过期，则尝试获得锁，开启线程去更新数据
        boolean isLock = tryLock(lockKey);
        //获取锁成功
        if(isLock){
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                //重建缓存
                try {
                    R apply = dbFallBack.apply(id);
                    this.setWithLogicExpire(key,apply,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }

        //返回商铺信息，无论过期与否
        return r;
    }

    //利用互斥锁解决缓存击穿问题
    public <R, ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String Json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(Json)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(Json, type);
        }
        // 判断命中的是否是空值，有两种可能""和null
        if (Json != null) {
            // 返回一个错误信息
            return null;
        }

        // 4.实现缓存重建
        // 4.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        R r = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 4.2.判断是否获取成功
            if (!isLock) {
                // 4.3.获取锁失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
            }
            // 4.4.获取锁成功，根据id查询数据库
            r = dbFallback.apply(id);
            // 5.不存在，返回错误
            if (r == null) {
                // 将空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                // 返回错误信息
                return null;
            }
            // 6.存在，写入redis
            this.set(key, r, time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            // 7.释放锁
            unlock(lockKey);
        }
        // 8.返回
        return r;
    }





    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1",  LOCK_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }


}
