package com.example.butter.domain.dto;

import lombok.Data;

@Data
public class AddStoreReqDto {

    private String name;

    private String address;

    private Double lat;

    private Double lng;

    private String description;

}
