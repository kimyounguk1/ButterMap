package com.example.butter.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class OneStoreResDto {
    private Long id;

    private String name;

    private String description;

    private Long quantity;

    private String address;

    private Long price;
}
