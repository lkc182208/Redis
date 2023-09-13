package com.hmdp.service.impl;

import cn.hutool.cache.Cache;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheService;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

    @Autowired
    CacheService cacheService;
    @Override
    public Result getShopType() {
        //查询缓存
        List<String> stringList = cacheService.lRange(RedisConstants.SHOP_TYPE_KEY, 0, -1);

        //存在直接返回
        if(!stringList.isEmpty()){
            List<ShopType> shopTypeList = stringList.stream()
                    .map(item -> JSONUtil.toBean(item, ShopType.class))
                    .sorted(Comparator.comparing(ShopType::getSort))
                    .toList();
            return Result.ok(shopTypeList);
        }
        //不存在查询数据库
        List<ShopType> shopTypeList = query().list();
        //不存在返回错误
        if(shopTypeList.isEmpty()){
            return Result.fail("系统异常，请稍后重试");
        }
        //存在则添加到redis中
        List<String> list = shopTypeList.stream()
                .sorted(Comparator.comparingInt(ShopType::getSort))
                .map(item -> JSONUtil.toJsonStr(item))
                .toList();

        cacheService.lLeftPushAll(RedisConstants.SHOP_TYPE_KEY, list);
        //返回
        return Result.ok(shopTypeList);
    }
}
