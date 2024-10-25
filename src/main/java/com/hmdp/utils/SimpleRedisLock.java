package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import com.hmdp.service.impl.ShopTypeServiceImpl;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;


/**
 * @author liuyichen
 * @version 1.0
 */
public class SimpleRedisLock implements ILock{

    //当前线程请求的服务名称
    private String name;
    private StringRedisTemplate stringRedisTemplate;
    private static final String KEY_PREFIX="lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);

    }

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    @Override
    public boolean tryLock(Long timeoutSec) {
        //获取线程唯一标识
       String threadId = ID_PREFIX+ Thread.currentThread().getId();
       String lockKey = KEY_PREFIX+name;
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(b);//防止装箱时引起的空指针异常
    }

    @Override
    public void unlock() {
        String threadId = ID_PREFIX+ Thread.currentThread().getId();
        //进行判断，锁中的线程唯一标识与自己是否相同，相同才执行删除逻辑,为了保证操作的原子性，利用lua脚本实现
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(KEY_PREFIX+name), threadId);

    }
}
