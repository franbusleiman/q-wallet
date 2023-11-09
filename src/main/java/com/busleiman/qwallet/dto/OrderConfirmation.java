package com.busleiman.qwallet.dto;

import com.busleiman.qwallet.model.OrderState;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@NoArgsConstructor
@SuperBuilder
public class OrderConfirmation {
    private String id;
    private String sellerDni;
    private String orderState;
}
