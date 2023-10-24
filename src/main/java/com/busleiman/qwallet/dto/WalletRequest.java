package com.busleiman.qwallet.dto;


import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@NoArgsConstructor
@SuperBuilder
public class WalletRequest {
    private String orderId;
    private String buyerDni;
    private String sellerDni;
    private Long usdAmount;
    private Long javaCoinPrice;
}