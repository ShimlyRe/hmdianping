local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]
local stockkey = "seckill:stock:" .. voucherId  -- 补充冒号，保持键名规范
local orderkey = "seckill:order:" .. voucherId  -- 补充冒号，保持键名规范

-- 1. 检查库存是否存在且充足
local stock = tonumber(redis.call("get", stockkey))
if not stock or stock <= 0 then
    return 1  -- 库存不足或不存在
end

-- 2. 检查用户是否已下单
if redis.call("sismember", orderkey, userId) == 1 then
    return 2  -- 已购买
end

-- 3. 扣减库存并记录下单用户
redis.call("incrby", stockkey, -1)  -- 修正命令拼写
redis.call("sadd", orderkey, userId)
redis.call("xadd","stream.orders","*","userId",userId,"voucherId",voucherId,"id",orderId)
return 0