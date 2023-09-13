package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.CacheService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    CacheService cacheService;
    @Autowired
    CacheClient cacheClient;

    //查询商铺信息
    @Override
    public Result findById(Long id) {
        //缓存穿透
        //Shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES, RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById);
        //缓存击穿 互斥锁方案
        //Shop = queryWithMutex(id);

        //缓存击穿 逻辑过期方案  时间为 当前时间+秒数=过期时间
        Shop shop = cacheClient.queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, id2 -> getById(id2), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //Shop shop = queryWithLogicalExpire(id);
        if (shop == null) {
            return Result.fail("商铺信息不存在");
        }
        return Result.ok(shop);
    }
    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //逻辑过期解决缓存击穿 (逻辑过期：redis中的数据永不过期，添加了一个过期字段，所以不用考虑缓存穿透的问题(因为没有空值))
    private Shop queryWithLogicalExpire(Long id) {
        if (id <= 0) {
            return null;
        }
        //1.从缓存中查询
        Map<Object, Object> objectMap = cacheService.hGetAll(RedisConstants.CACHE_SHOP_KEY + id);

        //2.转java对象
        RedisData redisData = BeanUtil.mapToBean(objectMap, RedisData.class, false);

        //3.反序列化为对象
        Shop shop = JSONUtil.toBean(JSONUtil.toJsonStr(redisData.getData()), Shop.class);
        //过期时间
        LocalDateTime expireTime = redisData.getExpireTime();
        //4.判断有没有过期   过期时间是否在当前时间之后
        boolean flag = expireTime.isAfter(LocalDateTime.now());
        //没有过期直接返回
        if(flag){
            //没有过期，直接返回商铺信息
            return shop;
        }
        //5.过期了则获取互斥锁，重建缓存
        String key = RedisConstants.LOCK_SHOP_KEY+id;
        boolean lock = tryLock(key);

        if(lock){
            //获取互斥锁成功，再查缓存
            {
                //1.从缓存中查询
                Map<Object, Object> objectMap2 = cacheService.hGetAll(RedisConstants.CACHE_SHOP_KEY + id);

                //2.转java对象
                RedisData redisData2 = BeanUtil.mapToBean(objectMap2, RedisData.class, false);

                //3.反序列化为对象
                Shop shop2 = JSONUtil.toBean(JSONUtil.toJsonStr(redisData2.getData()), Shop.class);
                //过期时间
                LocalDateTime expireTime2 = redisData2.getExpireTime();
                //4.判断有没有过期
                boolean flag2 = expireTime2.isAfter(LocalDateTime.now());
                //没有过期直接返回
                if(flag2){
                    //没有过期，直接返回商铺信息
                    return shop2;
                }
            }
            //开启新的线程重建数据
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //调用缓存重建的方法
                    saveShopToRedis(id,20L);
                } catch (Exception e){
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(key);
                }
            });
        }
        //返回过期的商铺信息
        return shop;
    }

    //互斥锁解决缓存击穿
    private Shop queryWithMutex(Long id) {
        if (id <= 0) {
            return null;
        }
        //1.从缓存中查询
        Map<Object, Object> objectMap = cacheService.hGetAll(RedisConstants.CACHE_SHOP_KEY + id);
        //2.缓存命中
        if (!objectMap.isEmpty()) {
            //判断是否为有效对象
            if (objectMap.get("id").equals("-1")) {
                return null;
            }
            //转java对象
            Shop shop = BeanUtil.mapToBean(objectMap, Shop.class, false);
            //返回
            return shop;
        }
        //3.用互斥锁 重建缓存
        String key = null;
        Shop shop;
        try {
            //互斥锁设置key
            key = RedisConstants.LOCK_SHOP_KEY + id;
            //获取互斥锁
            boolean lock = tryLock(key);

            if (!lock) {
                //获取锁失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //4.获取锁成功，再查缓存
            //从缓存中查询
            Map<Object, Object> map = cacheService.hGetAll(RedisConstants.CACHE_SHOP_KEY + id);
            //2.缓存命中
            if (!objectMap.isEmpty()) {
                //判断是否为有效对象
                if (objectMap.get("id").equals("-1")) {
                    return null;
                }
                //转java对象
                shop = BeanUtil.mapToBean(objectMap, Shop.class, false);
                //返回
                return shop;
            }
            //5.缓存中没有，查询数据库
            shop = getById(id);
            /*模拟重建延时
            Thread.sleep(200);*/
            if (shop == null) {
                //数据库中为空，设置空值解决缓存穿透
                shop = new Shop();
                shop.setId(-1L);
                Map stringObjectMap = BeanUtil.beanToMap(shop);
                stringObjectMap.forEach((shopKey, shopValue) -> {
                    if (shopValue != null) stringObjectMap.put(shopKey, shopValue.toString());
                });
                cacheService.hPutAll(RedisConstants.CACHE_SHOP_KEY + id, stringObjectMap);
                //设置过期时间
                cacheService.expire(RedisConstants.CACHE_SHOP_KEY + id, RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6.数据库中有，则添加到缓存中
            Map shopMap = BeanUtil.beanToMap(shop, new HashMap<>(),
                    CopyOptions.create()
                            .setIgnoreNullValue(true)
                            .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

            cacheService.hPutAll(RedisConstants.CACHE_SHOP_KEY + id, shopMap);
            //设置超时时间 缓存更新策略(超时剔除)
            cacheService.expire(RedisConstants.CACHE_SHOP_KEY + id, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放互斥锁
            unLock(key);
        }
        //5.返回结果
        return shop;
    }

    //缓存穿透
    private Shop queryWithPassThrough(Long id) {
        if (id <= 0) {
            return null;
        }
        //1.从缓存中查询
        Map<Object, Object> objectMap = cacheService.hGetAll(RedisConstants.CACHE_SHOP_KEY + id);
        //2.缓存命中
        if (!objectMap.isEmpty()) {
            //判断是否为有效对象
            if (objectMap.get("id").equals("-1")) {
                return null;
            }
            //转java对象
            Shop shop = BeanUtil.mapToBean(objectMap, Shop.class, false);
            //返回
            return shop;
        }
        //3.没有就查询数据库
        Shop shop = getById(id);

        if (shop == null) {
            //数据库中为空  解决缓存穿透;
            //缓存空数据
            shop = new Shop();

            shop.setId(-1L);

            Map stringObjectMap = BeanUtil.beanToMap(shop);

            stringObjectMap.forEach((key, value) -> {
                if (value != null) stringObjectMap.put(key, value.toString());
            });

            cacheService.hPutAll(RedisConstants.CACHE_SHOP_KEY + id, stringObjectMap);
            //设置过期时间
            cacheService.expire(RedisConstants.CACHE_SHOP_KEY + id, RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);

            return null;
        } else {
            //4.添加到缓存中
            Map shopMap = BeanUtil.beanToMap(shop, new HashMap<>(),
                    CopyOptions.create()
                            .setIgnoreNullValue(true)
                            .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

            cacheService.hPutAll(RedisConstants.CACHE_SHOP_KEY + id, shopMap);
            //设置超时时间 缓存更新策略(超时剔除)
            cacheService.expire(RedisConstants.CACHE_SHOP_KEY + id, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
            //5.返回结果
            return null;
        }
    }

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    //获取互斥锁
    public boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10L, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    //释放互斥锁
    public void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    //更新商铺信息  主动更新策略+超时剔除
    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        Long shopId = shop.getId();

        if (shopId == null) {
            return Result.fail("商铺信息更新失败");
        }

        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        cacheService.delete(RedisConstants.CACHE_SHOP_KEY + shopId);

        return Result.ok();
    }

    public void saveShopToRedis(Long id, Long expireSeconds) throws InterruptedException {
        RedisData redisData = new RedisData();
        //查询数据
        Shop shop = getById(id);
        //缓存重建延迟，延迟越高，线程安全问题越大
        Thread.sleep(200);

        //设置过期时间  测试时将数据设置成秒
        LocalDateTime expireTime = LocalDateTime.now().plusSeconds(expireSeconds);

        //LocalDateTime expireTime = LocalDateTime.now().plusMinutes(expireMinutes);
        redisData.setData(shop);
        redisData.setExpireTime(expireTime);
        //存入缓存
        Map stringObjectMap = BeanUtil.beanToMap(redisData);

        stringObjectMap.forEach((key, value) -> {
            if(!(value instanceof LocalDateTime)){
                stringObjectMap.put(key, JSONUtil.toJsonStr(value));
            }else {
                stringObjectMap.put(key,value.toString());
            }
        });

        cacheService.hPutAll(RedisConstants.CACHE_SHOP_KEY + id, stringObjectMap);
    }
}
