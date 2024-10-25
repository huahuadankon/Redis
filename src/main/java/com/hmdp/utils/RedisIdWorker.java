package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * @author liuyichen
 * @version 1.0
 */
@Component
public class RedisIdWorker {

    private final static long BEGIN_TIMESTAMP = 1729814400L;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    private static final int COUNT_BITS = 32;

    public long nextId(String prefix) {
        //1,生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond- BEGIN_TIMESTAMP;

        //2.生成序列号

        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + prefix + date);

        //3.拼接返回
        return timestamp << COUNT_BITS | count;



    }


}
