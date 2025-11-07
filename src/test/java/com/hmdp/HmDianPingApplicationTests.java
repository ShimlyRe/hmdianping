package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopServiceImpl;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IShopService shopService;
    private ExecutorService executorService = Executors.newFixedThreadPool(500);
    @Test
    void contextLoads() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(300);
        Runnable runnable = () -> {
            for (int i = 0; i < 100; i++) {
                Long id = redisIdWorker.nextId("order");
                System.out.println(id);
            }

        };
        long start = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            executorService.submit(runnable);
        }
        countDownLatch.await();
        long end = System.currentTimeMillis();
        System.out.println("<UNK>" + (end - start) + "ms");
    }
    @Test
    void testRedis() throws InterruptedException {
        List<Shop> list = shopService.list();

        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            Long typeId = entry.getKey();
            String key = SHOP_GEO_KEY + typeId;
            List<Shop> shops = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(shops.size());
            for (Shop shop : shops) {
//                stringRedisTemplate.opsForGeo().add(key,new Point(shop.getX(),shop.getY()),shop.getId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),new Point(shop.getX(),shop.getY())
                ));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);

        }
    }
}
