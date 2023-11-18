package com.busleiman.qwallet.dto;


import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@NoArgsConstructor
@SuperBuilder
public class OrderRequest {
    private Long id;
    private String buyerDni;
    private Double usdAmount;
    private Double javaCoinPrice;
}
