package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    private static final DefaultRedisScript<Long> SECKILL_VOUCHER_SCRIPT ;
    static {
        SECKILL_VOUCHER_SCRIPT = new DefaultRedisScript<>();
        SECKILL_VOUCHER_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_VOUCHER_SCRIPT.setResultType(Long.class);
    }

    private static final ExecutorService SECKILL_VOUCHER_EXECUTOR = Executors.newSingleThreadExecutor();
    @PostConstruct
    private void init(){
        SECKILL_VOUCHER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        private String queueName = "stream.orders";
        @Override
        public void run() {
            // 初始化消费者组
            try {
                // 创建消费者组，如果组已存在则忽略错误
                stringRedisTemplate.opsForStream().createGroup(queueName, "g1");
            } catch (Exception e) {
                log.warn("消费者组 g1 已存在，跳过创建");
            }
            while (true) {
                try {
                    //获取消息队列的订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1","c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    if (list == null || list.isEmpty()) {
                        //失败继续下一轮
                        continue;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    handlerVoucherOrder(voucherOrder);
                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("订单处理 异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    //获取消息队列的订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    if (list == null || list.isEmpty()) {
                        //失败,没有异常信息
                        break;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    handlerVoucherOrder(voucherOrder);
                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理handle-list异常", e.getMessage());
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }
    private IVoucherOrderService currentProxy;
//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
//    private class VoucherOrderHandler implements Runnable {
//        @Override
//        public void run() {
//            while(true){
//                try {
//                    VoucherOrder order = orderTasks.take();
//                    handlerVoucherOrder(order);
//                } catch (Exception e) {
//                    log.error("订单处理异常",e.getMessage());
//                }
//            }
//        }
//    }

    private void handlerVoucherOrder(VoucherOrder order) {

//        SimpleRedisLock simpleRedisLock = new SimpleRedisLock(stringRedisTemplate,"order:" + id);
        Long id = order.getUserId();
        // 创建锁对象
        RLock lock = redissonClient.getLock("lock:order" + id);
        //获取锁
        Boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("<重复下单>");
            return ;
        }
        try {
             currentProxy.create(order);
        } finally {
            lock.unlock();
        }
    }

    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        //执行lua脚本
        Long execute = stringRedisTemplate.execute(
                SECKILL_VOUCHER_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(),String.valueOf(orderId)
        );
        int result = execute.intValue();
        if (result != 0) {
            return Result.fail(result==1?"库存不足":"已购买");
        }


        currentProxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);

    }
//    public Result seckillVoucher(Long voucherId) {
//        Long userId = UserHolder.getUser().getId();
//        //执行lua脚本
//        Long execute = stringRedisTemplate.execute(SECKILL_VOUCHER_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(), userId.toString()
//        );
//        int result = execute.intValue();
//        if (result != 0) {
//            return Result.fail(result==1?"库存不足":"已购买");
//        }
//        //保存阻塞队列
//        VoucherOrder voucherOrder = new VoucherOrder();
//        Long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);
//        orderTasks.add(voucherOrder);
//        currentProxy = (IVoucherOrderService) AopContext.currentProxy();
//        return Result.ok(orderId);
//
//    }
    @Override
//    public Result seckillVoucher(Long voucherId) {
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("未开始");
//        }
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("已结束");
//        }
//        if (voucher.getStock() <= 0) {
//            return Result.fail("已抢完");
//        }
//        Long id = UserHolder.getUser().getId();
//        //创建锁对象
////        SimpleRedisLock simpleRedisLock = new SimpleRedisLock(stringRedisTemplate,"order:" + id);
//        RLock lock = redissonClient.getLock("lock:order" + id);
//        Boolean isLock = lock.tryLock();
//        if (!isLock) {
//            return Result.fail("<重复下单>");
//        }
//        try {
//            IVoucherOrderService currentProxy = (IVoucherOrderService) AopContext.currentProxy();
//            return currentProxy.create(voucherId);
//        } finally {
//            lock.unlock();
//        }
////        synchronized (id.toString().intern()){
////            //获取代理对象事务
////            IVoucherOrderService currentProxy = (IVoucherOrderService) AopContext.currentProxy();
////            return currentProxy.create(voucherId);
////        }
//
//
//    }

    @Transactional
    public void create(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            log.error("<已购买>");
            return;
        }
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)  //where id = ? and stock > 0
                .update();
        if (!success) {
            log.error("<已抢完>");
            return ;
        }
        //创建订单

        save(voucherOrder);

    }
}
