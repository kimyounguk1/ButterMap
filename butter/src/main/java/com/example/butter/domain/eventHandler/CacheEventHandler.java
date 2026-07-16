package com.example.butter.domain.eventHandler;

import com.example.butter.domain.entity.Store;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Set;

@Component
@RequiredArgsConstructor
public class CacheEventHandler {

    private final RedisTemplate<String, Object> redisTemplate;
    private final CacheManager localCacheManager;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleStoreCacheEvict(Store store) {
        Set<String> keys = redisTemplate.keys("store:*");
        if(keys!=null&&!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }

        Cache localCache = localCacheManager.getCache("storeList");
        if (localCache != null) {
            localCache.clear(); // 로컬 캐시 삭제
        }

    }

}
