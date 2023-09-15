package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.*;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    SeckillVoucherServiceImpl seckillVoucherService;

    @Autowired
    SeckillVoucherMapper seckillVoucherMapper;
    @Autowired
    CacheService cacheService;
    @Autowired
    RedisIdWorker redisIdWorker;
    @Autowired
    StringRedisTemplate stringRedisTemplate;

    //@Autowired springboot2.6版本之后，禁用了循环依赖
    //VoucherOrderServiceImpl proxy;

    //注入Bean容器
    @Autowired
    BeanFactory beanFactory;
    @Autowired
    RedissonClient redissonClient;

    //Lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final String queueName = "stream.orders";
    //启动类启动时初始化执行的方法
    @PostConstruct
    public void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private VoucherOrderServiceImpl proxy;
    public class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true){
                try {
                    //1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //2.判断消息获取是否成功
                    if(list.size() == 0 || list.isEmpty()){
                        //获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    //3.解析消息中的订单信息   只有一条消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.mapToBean(values, VoucherOrder.class, true);
                    //4.如果获取成功，可以下单
                    proxy.createVoucherOrder(voucherOrder);
                    //5.ACK确认  SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                }catch (Exception e){
                    log.error("订单状态异常:{}",e);
                    handlePendingList();
                }
            }
        }
        private void handlePendingList() {
            while (true){
                try {
                    //1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //2.如果消息为空，说明pending-list没有异常消息，结束循环
                    if(list.size() == 0 || list.isEmpty()){
                        //获取失败，说明没有消息，继续下一次循环
                        break;
                    }
                    //3.解析消息中的订单信息   只有一条消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.mapToBean(values, VoucherOrder.class, true);
                    //4.如果获取成功，可以下单
                    proxy.createVoucherOrder(voucherOrder);
                    //5.ACK确认  SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                }catch (Exception e){
                    log.error("订单状态异常:{}",e);
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }

    /**
     * 优惠券秒杀下单功能
     *
     * @param voucherId 优惠券id
     * @return 订单信息
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        //用户id
        Long userId = UserHolder.getUser().getId();
        //订单id
        long orderId = redisIdWorker.nextId("order");
        //1.执行Lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(),String.valueOf(orderId)
        );
        //2.判断是否有购买资格   0：可以购买  1：库存不足  2：不能重复下单
        if (result != 0) {
            //没有购买资格
            return Result.fail(result == 1 ? "库存不足" : "不能重复下单");
        }
        //3.获取代理对象
        proxy = (VoucherOrderServiceImpl) beanFactory.getBean("voucherOrderServiceImpl");

        return Result.ok(orderId);
    }
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //5.1一个用户只能下一单
        Long userId = voucherOrder.getUserId();
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            log.error("不允许重复购买！");
        }
        //6.扣减库存
        int i = seckillVoucherMapper.updateVoucher(voucherOrder.getVoucherId());
        //boolean flag = seckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", voucherId).update();
        //6.1更新失败也是库存不足
        if (i != 1) {
            log.error("库存不足！");
        }
        //7.生成订单信息

        //状态 订单状态 1未支付
        voucherOrder.setStatus(1);

        voucherOrder.setCreateTime(LocalDateTime.now());
        voucherOrder.setUpdateTime(LocalDateTime.now());

        baseMapper.insert(voucherOrder);
    }

    /*@Transactional
    public Result createVoucherOrder(Long voucherId) {
        //5.1一个用户只能下一单
        Long userId = UserHolder.getUser().getId();
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("您已抢过该券了！");
        }
        //6.扣减库存
        int i = seckillVoucherMapper.updateVoucher(voucherId);
        //boolean flag = seckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", voucherId).update();
        //6.1更新失败也是库存不足
        if (i != 1) {
            return Result.fail("秒杀券库存不足，抢券失败!");
        }
        //7.生成订单信息
        VoucherOrder voucherOrder = new VoucherOrder();
        //7.1获取当前下单的用户
        voucherOrder.setUserId(userId);
        //优惠券的id
        voucherOrder.setVoucherId(voucherId);
        //订单的id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //支付方式  1余额支付  2 支付宝 3微信
        voucherOrder.setPayType(3);
        //状态 订单状态 1未支付
        voucherOrder.setStatus(1);

        voucherOrder.setCreateTime(LocalDateTime.now());
        voucherOrder.setUpdateTime(LocalDateTime.now());

        baseMapper.insert(voucherOrder);
        //8.返回
        return Result.ok(orderId);
    }*/
    //判断数据库中库存，大量用户访问数据库影响性能，所以改为redis中判断库存
    /*@Override
    public Result seckillVoucher(Long voucherId) {
        //3.判断是否有效(秒杀是否开始)
        //3.1查询秒杀券详细信息
        SeckillVoucher seckillVoucher = seckillVoucherMapper.queryById(voucherId);
        if(seckillVoucher == null){
            return Result.fail("秒杀券不可用或已过期");
        }
        //3.3判断秒杀是否开始
        if(seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀活动未开始！");
        }
        //3.4判断秒杀是否结束
        if(seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀活动已结束！");
        }
        //5.判断库存是否充足
        Integer stock = seckillVoucher.getStock();
        if(stock <= 0){
            return Result.fail("秒杀券库存不足!");
        }

        Long userId = UserHolder.getUser().getId();
        //创建分布式锁对象
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        //
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        boolean isLock = lock.tryLock();
        //boolean isLock = simpleRedisLock.tryLock(1200L);
        if(!isLock){
            //获取锁不成功，说明已经在下单了
            return Result.fail("不允许重复下单");
        }
        //获取代理对象(事务)
        //1.AopContext中获取当前类的代理对象
        //IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
        //2.注入bean容器，拿当前类的代理对象
        try {
            VoucherOrderServiceImpl proxy = (VoucherOrderServiceImpl) beanFactory.getBean("voucherOrderServiceImpl");
            //3.注入当前类的对象(注入的对象为代理对象)  springboot2.6之后禁用了循环依赖，不能自己注入自己
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.unlock();
        }
    }*/
}
