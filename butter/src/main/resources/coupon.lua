-- KEYS[1]: 중복 체크용 Set 키 ('coupon:users')
-- ARGV[1]: 최대 수량 (예: 100)
-- ARGV[2]: 유저 ID

-- 1. 중복 발급 확인 (Set에 이미 있는지) KEYS[1]은 리스트가 들어와야함
if redis.call('SISMEMBER', KEYS[1], ARGV[2]) == 1 then
    return -1
end

-- 2. 현재 발급 수량 확인 (Set의 크기 확인)
local current_count = redis.call('SCARD', KEYS[1])
if tonumber(current_count) >= tonumber(ARGV[1]) then
    return 0
end

-- 3. 조건 통과: Set에 유저 추가 & DB 작업을 위한 Queue(List)에 추가
redis.call('SADD', KEYS[1], ARGV[2]) --추가
redis.call('LPUSH', 'coupon:queue', ARGV[2]) -- 리스트이 왼쪽에 추가 오른쪽부터 꺼낼 예정
redis.call('PUBLISH', 'coupon:alarm', 'NEW_REQUEST') -- redis publish

return 1