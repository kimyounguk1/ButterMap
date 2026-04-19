package com.example.butter.domain.service;

import com.example.butter.domain.dto.OrderRequestDto;
import com.example.butter.domain.entity.Order;
import com.example.butter.domain.entity.Store;
import com.example.butter.domain.repository.OrderRepository;
import com.example.butter.domain.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final RankingService rankingService; // 랭킹 서비스 주입
    private final StoreRepository storeRepository;


}
