package com.busleiman.qwallet.model;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;

@Data
@Builder
public class WalletAccount {

    @Id
    private String userDNI;

    private Long javaCoins;

}
