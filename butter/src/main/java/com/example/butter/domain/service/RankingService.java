package com.example.butter.domain.service;

import com.example.butter.domain.dto.StoreRankingDto;
import com.example.butter.domain.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import com.example.butter.domain.entity.Store;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RankingService {
    private final StringRedisTemplate redisTemplate;
    private final StoreRepository storeRepository; // 1. 매장 이름을 찾기 위해 Repository 주입 필요!
    private static final String RANKING_KEY = "store:ranking:daily";

    public void incrementOrderCount(Long storeId) {
        redisTemplate.opsForZSet().incrementScore(RANKING_KEY, storeId.toString(), 1);
        redisTemplate.convertAndSend("ranking:change:events", "updated");
    }

    public List<StoreRankingDto> getTopRankings(int limit) {
        Set<ZSetOperations.TypedTuple<String>> result =
                redisTemplate.opsForZSet().reverseRangeWithScores(RANKING_KEY, 0, limit - 1);

        if (result == null || result.isEmpty()) return Collections.emptyList();

        return result.stream()
                .map(tuple -> {
                    Long storeId = Long.parseLong(tuple.getValue());
                    int score = tuple.getScore().intValue();

                    // 2. DB에서 매장 이름을 찾아옵니다.
                    String storeName = storeRepository.findById(storeId)
                            .map(Store::getName)
                            .orElse("이름 없는 매장");

                    // 3. 인자 3개를 다 채워서 생성자를 호출합니다! (ID, 이름, 점수 순서)
                    return new StoreRankingDto(storeId, storeName, score);
                })
                .collect(Collectors.toList());
    }
}