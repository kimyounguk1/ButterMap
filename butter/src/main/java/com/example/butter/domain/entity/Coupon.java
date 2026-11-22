package com.example.butter.domain.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Entity(name = "coupons")
// 같은 메시지가 재전달(at-least-once)되더라도 유저당 쿠폰은 1장만 저장되도록 DB 레벨에서 보장
@Table(name = "coupons", uniqueConstraints = @UniqueConstraint(name = "uk_coupons_user_id", columnNames = "user_id"))
@NoArgsConstructor
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    public Coupon (User user) {
        this.user = user;
    }

}
