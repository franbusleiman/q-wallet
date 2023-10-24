package com.busleiman.qwallet.model;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Builder
@Table("ORDERS")
public class Order {
    @Id
    private String id;
    private String buyerDni;
    private String sellerDni;
    private  Long javaCoinsAmount;
    private OrderState orderState;
}
