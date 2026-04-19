package com.example.butter.domain.entity;

import com.example.butter.domain.dto.AddStoreReqDto;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@Entity(name = "stores")
public class Store {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String address;

    private Long quantity;

    private Double lng;

    private Double lat;

    private String information;

    private Long price;

    @OneToMany(mappedBy = "store", cascade = CascadeType.ALL, orphanRemoval = true)
    List<Order> orders = new ArrayList<>();

    public Store(AddStoreReqDto addStoreReqDto) {
        this.name = addStoreReqDto.getName();
        this.address = addStoreReqDto.getAddress();
        this.lat = addStoreReqDto.getLat();
        this.lng = addStoreReqDto.getLng();
        this.information = addStoreReqDto.getDescription();
        this.quantity = (long) 100;
    }
}
