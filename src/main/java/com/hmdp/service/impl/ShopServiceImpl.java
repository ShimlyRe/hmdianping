package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisCommand;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;



    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queryWithMutex(id);
        //Shop shop = queryWithLogicExpire(id);
        Shop shop = cacheClient.queryPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }
//    public Shop queryWithMutex(Long id) {
//        String shop = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//        if (StrUtil.isNotBlank(shop)) {
//            return JSONUtil.toBean(shop, Shop.class);
//        }
//        if (shop!=null){
//            return null;
//        }
//        String lockkey = LOCK_SHOP_KEY + id;
//        Shop shop1 = null;
//        try {
//            Boolean islock = tryLock(lockkey);
//            if (!islock){
//                Thread.sleep(50);
//                return queryWithMutex(id);
//            }
//            //成功查到数据
//            shop1 = getById(id);
//            Thread.sleep(200);
//            if (shop1 == null) {
//                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
//                return null;
//            }
//
//            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop1),CACHE_SHOP_TTL, TimeUnit.MINUTES);
//
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        } finally {
//            unlock(lockkey);
//        }
//        return shop1;
//    }
//    public Shop queryWithPassThrough(Long id) {
//        String shop = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//        if (StrUtil.isNotBlank(shop)) {
//            return JSONUtil.toBean(shop, Shop.class);
//        }
//        if (shop!=null){
//            return null;
//        }
//        Shop shop1 = getById(id);
//        if (shop1 == null) {
//            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
//            return null;
//        }
//        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop1),CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        return shop1;
//    }

//    public void saveshop2Redis(Long id,Long expireSeconds) throws InterruptedException {
//        Shop shop = getById(id);
//        Thread.sleep(200);
//        RedisData redisData = new RedisData();
//        redisData.setData(shop);
//        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
//        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id , JSONUtil.toJsonStr(redisData));
//    }
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("<店铺Id为空>");
        }
        //1更新数据库
        updateById(shop);

        //2删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if(x==null||y==null){
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int to = current * SystemConstants.DEFAULT_PAGE_SIZE;
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        key, GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeCoordinates()
                                .limit(to)
                );
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = new ArrayList<>(list.size());
        Map<String,Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(geoResult -> {
            String shopId = geoResult.getContent().getName();
            ids.add(Long.parseLong(shopId));
            Distance distance = geoResult.getDistance();
            distanceMap.put(shopId, distance);
        });
        String idstr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idstr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}
