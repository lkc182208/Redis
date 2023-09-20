package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.*;
import com.lkc.file.service.FileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.ZSetOperations;

import javax.annotation.Resource;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


@SpringBootTest(classes = HmDianPingApplication.class)
class HmDianPingApplicationTests {

    @Test
    void T2() {
        String a = "ajkdfs.jpg";
        String string = UUID.randomUUID().toString(true);
        String substring = a.substring(a.lastIndexOf("."));
        System.out.println(string + substring);
    }

    @Autowired
    CacheService cacheService;
    @Test
    void name() {
        UserDTO userDTO = new UserDTO();
        userDTO.setNickName("小红");
        userDTO.setId(14L);
        userDTO.setIcon("照片");

        UserDTO userDTO1 = new UserDTO();
        userDTO1.setNickName("小白");
        userDTO1.setId(13L);
        userDTO1.setIcon("哈哈");

        List<UserDTO> list = new ArrayList<>();

        list.add(userDTO1);
        list.add(userDTO);
        String jsonStr = JSONUtil.toJsonStr(list);

        System.out.println(jsonStr);
    }

    @Autowired
    ShopServiceImpl shopService;
    @Test
    void aaa() throws InterruptedException {

        //1.从缓存中查询
        Map<Object, Object> objectMap = cacheService.hGetAll(RedisConstants.CACHE_SHOP_KEY + 1);

        //2.转java对象
        RedisData redisData = BeanUtil.mapToBean(objectMap, RedisData.class, false);

        //3.反序列化为对象
        Object data = redisData.getData();

        String jsonStr = JSONUtil.toJsonStr(data);

        Shop shop = JSONUtil.toBean(jsonStr, Shop.class);
        System.out.println(shop);

        System.out.println(redisData.getExpireTime());


    }

    @Autowired
    CacheClient cacheClient;

    @Test
    void sss() {
        Shop byId = shopService.getById(1L);

        cacheClient.setWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY+1L,byId,30L, TimeUnit.MINUTES);
    }

    @Autowired
    RedisIdWorker redisIdWorker;
    private ExecutorService executorService = Executors.newFixedThreadPool(500);
    @Test
    void vvv() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id =" +id);
            }
            latch.countDown();
        };
        long start = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            executorService.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - start));
    }

    @Resource
    FileStorageService fileStorageService;
    @Test
    void testminio() throws FileNotFoundException {
        FileInputStream fileInputStream = new FileInputStream("D:\\reggie8.jpg");
        String utl = fileStorageService.uploadImgFile(null, "reggie8.jpg", fileInputStream);
        System.out.println(utl);
    }

    @Test
    void testminio1() throws InterruptedException {
        //zset
        String str = String.valueOf(new Date().getTime());
        System.out.println("str = " + str);
        Double valueOf = Double.valueOf(str);
        System.out.println("double = " + valueOf);

        DefaultTypedTuple typedTuple1 = new DefaultTypedTuple("1",Double.valueOf(String.valueOf(new Date().getTime())));
        Thread.sleep(20);
        //DefaultTypedTuple typedTuple2 = new DefaultTypedTuple("4",Double.valueOf(String.valueOf(new Date().getTime())));
        Set<ZSetOperations.TypedTuple<String>> set = new HashSet<>();
        set.add(typedTuple1);
        Long l = cacheService.zAdd(RedisConstants.BLOG_LIKED_KEY+"9", set);
        System.out.println("l = " + l);
    }

    @Autowired
    UserMapper userMapper;
    @Test
    void testminio2() {
        Set<String> set = cacheService.sIntersect(RedisConstants.FOLLOW_USER_KEY + "1012", RedisConstants.FOLLOW_USER_KEY + "1013");

        System.out.println(set);
    }
}
