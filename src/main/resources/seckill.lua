-- 1.参数列表
-- 1.1 优惠券id
local voucherId = ARGV[1]
-- 1.2 用户id
local userId = ARGV[2]

-- 2.数据key
-- 2.1 库存key
local stockkey = 'seckill:stock:' .. voucherId
-- 2.2 订单key
local orderkey = 'seckill:order:' .. voucherId

-- 3.脚本业务
-- 3.1 判断库存是否充足 get stockkey
if(tonumber(redis.call('get',stockkey)) <= 0) then
    -- 3.2 库存不足 返回1
    return 1
end
-- 3.2判断用户是否下单 SISMEMBER orderkey userId
if(redis.call('sismember', orderkey,userId) == 1) then
    -- 3.3存在，说明是重复下单，返回2
    return 2
end
-- 3.4 扣库存 incrby stockkey -1
redis.call('incrby',stockkey,-1)
-- 3.5 下单(保存用户) sadd orderkey userId
redis.call('sadd',orderkey,userId)

return 0