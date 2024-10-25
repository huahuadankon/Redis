package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    private static final long CACHE_SHOP_TYPE_TTL = 60 * 60;
    @Override
    public List<ShopType> queryShopType() {
        String key = "cache:shopType:list";
        String shopTypeJson = stringRedisTemplate.opsForValue().get(key);
        if (shopTypeJson != null) {
            List<ShopType> typeList = JSONUtil.toList(shopTypeJson, ShopType.class);
            return typeList;
        }

        // 3. 如果缓存中没有数据，则查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(typeList), CACHE_SHOP_TYPE_TTL, TimeUnit.SECONDS);

        return typeList;
    }
}
