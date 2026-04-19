package com.example.butter.domain.service;

import com.example.butter.domain.controller.Controller;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RankingUpdateListener implements MessageListener {

    private final Controller rankingSseController;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        // "ranking:change:events" 채널에 메시지가 오면 호출됨
        // 신호를 받자마자 SSE 접속자 전원에게 랭킹 브로드캐스트
        rankingSseController.broadcastAll();
    }
}