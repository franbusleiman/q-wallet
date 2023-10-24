package com.busleiman.qwallet.dto;

import com.busleiman.qwallet.model.OrderState;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@NoArgsConstructor
@SuperBuilder
public class OrderRequest {
    private String id;
    private String buyerDni;
    private String sellerDni;
    private  Long javaCoinsAmount;
    private Long javaCoinPrice;
    private OrderState orderState;
}