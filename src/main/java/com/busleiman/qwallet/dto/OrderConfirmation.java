package com.busleiman.qwallet.dto;

import com.busleiman.qwallet.model.OrderState;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@NoArgsConstructor
@SuperBuilder
public class OrderConfirmation {
    private Long id;
    private String sellerDni;
    private OrderState orderState;
    private String errorDescription;
    private SourceType sourceType = SourceType.WALLET;
}
