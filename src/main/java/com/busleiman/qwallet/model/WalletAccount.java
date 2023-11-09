package com.busleiman.qwallet.model;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Builder
@Table("WALLET_ACCOUNT")
public class WalletAccount {

    @Id
    private String userDNI;

    private Long javaCoins;

}
