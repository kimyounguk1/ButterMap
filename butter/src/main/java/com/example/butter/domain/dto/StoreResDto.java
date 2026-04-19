package com.example.butter.domain.dto;

import com.example.butter.domain.entity.Store;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class StoreResDto {

    private Long id;

    private String name;

    private Double lat;

    private Double lng;

    private String description;

    private Long quantity;

    private String address;

    private Long price;

    public StoreResDto(Store store) {
        this.id = store.getId();
        this.name = store.getName();
        this.lat = store.getLat();
        this.lng = store.getLng();
        this.description = store.getInformation();
        this.quantity = store.getQuantity();
        this.address = store.getAddress();
        this.price = store.getPrice();
    }

}
