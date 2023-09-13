package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;

@Slf4j
@Component
public class CacheClient {
    private StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //hash类型
    public void set(String key, Object value, Long time, TimeUnit unit) {
        //存数据
        stringRedisTemplate.opsForHash().putAll(key, (Map<?, ?>) value);
        //设置过期值 缓存更新策略
        stringRedisTemplate.expire(key, time, unit);
    }

    //设置逻辑过期
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入redis
        Map stringObjectMap = BeanUtil.beanToMap(redisData);
        //对map集合整理
        stringObjectMap.forEach((shopKey, shopValue) -> {
            if (!(shopValue instanceof LocalDateTime)) {
                stringObjectMap.put(shopKey, JSONUtil.toJsonStr(shopValue));
            } else {
                stringObjectMap.put(shopKey, shopValue.toString());
            }
        });
        stringRedisTemplate.opsForHash().putAll(key, stringObjectMap);
    }

    //缓存穿透封装
    public <R, ID> R queryWithPassThrough(Long time, TimeUnit unit, String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack) {
        String key = keyPrefix + id;
        //1.从缓存中查询
        Map<Object, Object> objectMap = stringRedisTemplate.opsForHash().entries(key);
        //2.缓存命中
        if (!objectMap.isEmpty()) {
            //判断是否为有效对象
            if (objectMap.get("id").equals("-1")) {
                return null;
            }
            //转java对象
            R r = BeanUtil.mapToBean(objectMap, type, false);
            //返回
            return r;
        }
        //3.没有就查询数据库
        R r = dbFallBack.apply(id);

        if (r == null) {
            //数据库中为空  解决缓存穿透;
            //缓存空数据
            Map stringObjectMap = new HashMap<>();

            stringObjectMap.put("id", "-1");
            //存入空值
            stringRedisTemplate.opsForHash().putAll(key, stringObjectMap);
            //设置过期时间
            stringRedisTemplate.expire(key, CACHE_NULL_TTL, TimeUnit.MINUTES);

            return null;
        }
        //4.添加到缓存中
        Map shopMap = BeanUtil.beanToMap(r, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

        this.set(key, shopMap, time, unit);
        //5.返回结果
        return r;
    }

    //获取互斥锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10L, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    //释放互斥锁
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //逻辑过期解决缓存击穿 (逻辑过期：redis中的数据永不过期，添加了一个过期字段，所以不用考虑缓存穿透的问题(因为没有空值))
    public <R,ID> R queryWithLogicalExpire(String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallBack,Long time,TimeUnit unit) {
        String key = keyPrefix + id;
        //1.从缓存中查询
        Map<Object, Object> objectMap = stringRedisTemplate.opsForHash().entries(key);

        //2.反序列化为java对象
        RedisData redisData = BeanUtil.mapToBean(objectMap, RedisData.class, false);

        //3.获取java对象
        R r = JSONUtil.toBean(JSONUtil.toJsonStr(redisData.getData()), type);
        //过期时间
        LocalDateTime expireTime = redisData.getExpireTime();
        //4.判断有没有过期   过期时间是否在当前时间之后
        boolean flag = expireTime.isAfter(LocalDateTime.now());
        //没有过期直接返回
        if(flag){
            //没有过期，直接返回商铺信息
            return r;
        }
        //5.过期了则获取互斥锁，重建缓存
        String lockKey = RedisConstants.LOCK_SHOP_KEY+id;
        boolean lock = tryLock(lockKey);

        if(lock){
            //获取互斥锁成功，再查缓存
            {
                //1.从缓存中查询
                Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(key);

                //2.反序列化为java对象
                RedisData data = BeanUtil.mapToBean(map, RedisData.class, false);

                //3.获取java对象
                R r1 = JSONUtil.toBean(JSONUtil.toJsonStr(data.getData()), type);
                //过期时间
                LocalDateTime expireTime1 = data.getExpireTime();
                //4.判断有没有过期   过期时间是否在当前时间之后
                boolean flag1 = expireTime1.isAfter(LocalDateTime.now());
                //没有过期直接返回
                if(flag1){
                    //没有过期，直接返回商铺信息
                    return r1;
                }
            }
            //开启新的线程重建数据
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //查询数据库
                    R r2 = dbFallBack.apply(id);
                    //此睡眠只为测试提供
                    Thread.sleep(200);
                    //存入redis
                    this.setWithLogicalExpire(key,r2,time,unit);
                } catch (Exception e){
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }
        //返回过期的商铺信息
        return r;
    }
}
