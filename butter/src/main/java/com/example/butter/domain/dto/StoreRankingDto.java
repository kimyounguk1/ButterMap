package com.example.butter.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class StoreRankingDto {
    private Long storeId;      // 매장 식별자
    private String storeName;  // 매장 이름 (화면에 표시용)
    private int score;         // 주문 횟수 (또는 점수)
}