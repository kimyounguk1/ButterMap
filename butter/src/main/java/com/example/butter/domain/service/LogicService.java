package com.example.butter.domain.service;

import com.example.butter.domain.dto.*;
import com.example.butter.domain.entity.Order;
import com.example.butter.domain.entity.Store;
import com.example.butter.domain.entity.User;
import com.example.butter.domain.repository.CouponRepository;
import com.example.butter.domain.repository.OrderRepository;
import com.example.butter.domain.repository.StoreRepository;
import com.example.butter.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Service
@Slf4j
public class LogicService {

    private final StoreRepository storeRepository;
    private final OrderRepository orderRepository;
    private final CouponRepository couponRepository;
    private final UserRepository userRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final RedisScript<Long> couponScript;
    private final RankingService rankingService;
    private final CacheManager localCacheManager;
    private final RedisTemplate<String, Object> hashRedisTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public LogicService(
            StoreRepository storeRepository,
            OrderRepository orderRepository,
            CouponRepository couponRepository,
            UserRepository userRepository,
            RedisTemplate<String, String> redisTemplate,
            RedisScript<Long> couponScript,
            RankingService rankingService,
            @Qualifier("localCacheManager") CacheManager localCacheManager,
            @Qualifier("redisTemplate") RedisTemplate<String, Object> hashRedisTemplate,
            ApplicationEventPublisher eventPublisher
    ) {
        this.storeRepository = storeRepository;
        this.orderRepository = orderRepository;
        this.couponRepository = couponRepository;
        this.userRepository = userRepository;
        this.redisTemplate = redisTemplate;
        this.couponScript = couponScript;
        this.rankingService = rankingService;
        this.localCacheManager = localCacheManager;
        this.hashRedisTemplate = hashRedisTemplate;
        this.eventPublisher = eventPublisher;
    }

    public List<StoreResDto> getStore() {
        log.info("db로 조회");
        List<Store> all = storeRepository.findAll();
        return all.stream().map(StoreResDto::new).toList();
    }

    public List<StoreResDto> getStoreList() {
        String idsKey = "store:ids";

        //로컬캐시 조회
        Cache localCache = localCacheManager.getCache("storeList"); // 캐시 이름 설정
        if (localCache != null) {
            List<StoreResDto> cachedList = localCache.get(idsKey, List.class);
            if (cachedList != null) {
                return cachedList;
            }
        }

        Object lock = locks.computeIfAbsent(idsKey, k -> new Object());
        synchronized (lock) {
            //redis에서 순차적 처리전에 앞요청이 로컬캐시를 채워놓았는지 확인(redis, db를 1회만 조회 보장)
            if (localCache != null) {
                List<StoreResDto> doubleCheck = localCache.get(idsKey, List.class);
                if (doubleCheck != null) return doubleCheck;
            }

            List<Object> idList = hashRedisTemplate.opsForList().range(idsKey, 0, -1);

            // 1. 목록 자체가 없으면 DB행
            if (idList == null || idList.isEmpty()) {
                List<StoreResDto> storeResDtos = getStoreResDtos();
                if (localCache != null) {
                    localCache.put(idsKey, storeResDtos);
                }
                return storeResDtos;
            }

            // 2. 파이프라인으로 한 번에 긁어오기
            List<Object> pipelinedResults = hashRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (Object id : idList) {
                    connection.hashCommands().hGetAll(("store:" + id).getBytes());
                }
                return null;
            });

            // filter나 추가 DB 조회를 없애고 단순하게 처리
            if (pipelinedResults.contains(null) || pipelinedResults.stream().anyMatch(res -> ((Map<?, ?>) res).isEmpty())) {
                log.warn("캐시 일부 누락 발견. 전체 재동기화 진행");
                List<StoreResDto> dbData = getStoreResDtos();
                if (localCache != null) localCache.put(idsKey, dbData); // 로컬 캐시 갱신
                return dbData;
            }

            if (ThreadLocalRandom.current().nextDouble() < 0.001) {
                extendAllCacheTtl(idsKey, idList);
            }

            // 4. 모두 다 있다면 DTO 변환만 해서 끝
            List<StoreResDto> list = pipelinedResults.stream()
                    .map(res -> convertToStoreResDto((Map<Object, Object>) res))
                    .toList();

            if (localCache != null) {
                localCache.put(idsKey, list);
            }
            return list;
        }
    }

    //radis Jitter 적용
    private void extendAllCacheTtl(String idsKey, List<Object> idList) {
        log.info("Jitter 동작");
        hashRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {

            long totalTtl = 3600 ;

            connection.keyCommands().expire(idsKey.getBytes(), totalTtl);
            for (Object id : idList) {
                connection.keyCommands().expire(("store:" + id).getBytes(), totalTtl);
            }
            return null;
        });
    }

    private List<StoreResDto> getStoreResDtos() {
        // DB 전체 조회
        List<Store> allStores = storeRepository.findAll();

        // 캐시 채우기 (ID 리스트 + 개별 Hash)
        String[] idArray = allStores.stream()
                .map(s -> String.valueOf(s.getId()))
                .toArray(String[]::new); // 배열로 변환
        hashRedisTemplate.opsForList().rightPushAll("store:ids", idArray);
        hashRedisTemplate.expire("store:ids", Duration.ofHours(1));
        allStores.forEach(this::saveStoreToCache);

        return allStores.stream().map(StoreResDto::new).toList();
    }

    private StoreResDto convertToStoreResDto(Map<Object, Object> map) {
        return new StoreResDto(
                Long.valueOf(map.get("id").toString()),
                map.get("name").toString(),
                Double.valueOf(map.get("lat").toString()),
                Double.valueOf(map.get("lng").toString()),
                map.get("description").toString(),
                Long.valueOf(map.get("quantity").toString()),
                map.get("address").toString(),
                Long.valueOf(map.get("price").toString())
        );
    }

    public OneStoreResDto getOneStore(Long id) {
        String key = "store:" + id;

        Map<Object, Object> map = hashRedisTemplate.opsForHash().entries(key);

        if(!map.isEmpty()){
            return new OneStoreResDto(
                    Long.valueOf(map.get("id").toString()),
                    map.get("name").toString(),
                    map.get("description").toString(),
                    Long.valueOf(map.get("quantity").toString()),
                    map.get("address").toString(),
                    Long.valueOf(map.get("price").toString())
            );
        }

        Store store = storeRepository.findById(id).orElse(null);
        saveStoreToCache(store);

        return new OneStoreResDto(
                store.getId(),
                store.getName(),
                store.getInformation(),
                store.getQuantity(),
                store.getAddress(),
                store.getPrice()
        );
    }

    private void saveStoreToCache(Store store) {
        String key = "store:" + store.getId();
        Map<String, String> map = new HashMap<>();
        map.put("id", store.getId().toString());
        map.put("name", store.getName());
        map.put("lat", store.getLat().toString());
        map.put("lng", store.getLng().toString());
        map.put("description", store.getInformation()); // DTO 필드명에 맞춤
        map.put("quantity", store.getQuantity().toString());
        map.put("address", store.getAddress());
        map.put("price", store.getPrice().toString());

        hashRedisTemplate.opsForHash().putAll(key, map);
        hashRedisTemplate.expire(key, Duration.ofHours(1));
    }

    @Transactional
    //@EventListener를 쓰면 commit이후 동작이 보장되지 않음
    public void suggestStore(AddStoreReqDto addStoreReqDto) {

        Store store = new Store(addStoreReqDto);

        Store save = storeRepository.save(store);

        eventPublisher.publishEvent(save);

    }

    public void getCoupon(CouponReqDto couponReqDto) {
        String limit = "100"; // 선착순 인원 설정

        // 루아 스크립트 실행
        Long result = redisTemplate.execute(
                couponScript,
                List.of(CouponWorker.USERS_KEY, CouponWorker.STREAM_KEY), // KEYS[1], KEYS[2]
                limit,                         // ARGV[1]
                String.valueOf(couponReqDto.getUserId()) // ARGV[2]
        );

        if (result == -1) {
            throw new RuntimeException("이미 참여하셨습니다.");
        } else if (result == 0) {
            throw new RuntimeException("모든 쿠폰이 소진되었습니다.");
        }

        // result == 1 이면 성공 (이미 루아 스크립트 내부에서 Stream에 적재됨)
        System.out.println("발급 대기열 등록 성공: " + couponReqDto.getUserId());

    }

    public void createOrder(OrderRequestDto dto) {
        // 1. 매장과 유저가 존재하는지 확인
        Store store = storeRepository.findById(dto.getStoreId())
                .orElseThrow(() -> new RuntimeException("매장을 찾을 수 없습니다."));
        User user = userRepository.findById(dto.getUserId())
                .orElseThrow(() -> new RuntimeException("유저를 찾을 수 없습니다."));

        // 2. 주문 엔티티 생성 및 저장
        Order order = new Order(store, user);
        orderRepository.save(order);

        // 3. 랭킹 업데이트 호출! (Redis 점수 1점 상승)
        rankingService.incrementOrderCount(store.getId());
    }

    public String getLocalCache(String q) {
        Cache cache = localCacheManager.getCache(q);
        if(cache != null) {
            return cache.get("key", String.class);
        }
        return null;
    }

    public String saveLocalCache(String q) {
        Cache cache = localCacheManager.getCache(q);
        if(cache != null) {
            cache.put("key", q);
            return "Saved" + q;
        }
        return "Cache not found";
    }


}
