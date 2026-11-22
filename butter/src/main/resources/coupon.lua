-- KEYS[1]: 중복 체크용 Set 키 ('coupon:users')
-- KEYS[2]: DB 저장 작업용 Stream 키 ('coupon:stream')
-- ARGV[1]: 최대 수량 (예: 100)
-- ARGV[2]: 유저 ID

-- 1. 중복 발급 확인 (Set에 이미 있는지)
if redis.call('SISMEMBER', KEYS[1], ARGV[2]) == 1 then
    return -1
end

-- 2. 현재 발급 수량 확인 (Set의 크기 확인)
local current_count = redis.call('SCARD', KEYS[1])
if tonumber(current_count) >= tonumber(ARGV[1]) then
    return 0
end

-- 3. 조건 통과: Set에 유저 추가 & DB 저장 작업을 Stream에 적재
--    Set 추가와 Stream 적재가 한 스크립트 안에서 원자적으로 실행되므로
--    "당첨은 됐는데 저장 작업이 없는" 상태는 생기지 않는다.
--    Stream은 Consumer Group이 XACK 하기 전까지 메시지를 PEL에 보관하므로
--    워커가 DB 저장 중 죽어도 메시지가 유실되지 않는다. (기존 List + RPOP 방식의 유실 문제 해결)
redis.call('SADD', KEYS[1], ARGV[2])
redis.call('XADD', KEYS[2], '*', 'userId', ARGV[2])

return 1
