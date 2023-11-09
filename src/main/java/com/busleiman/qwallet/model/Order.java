package com.busleiman.qwallet.model;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Builder
@Table("ORDERS")
public class Order implements Persistable {
    @Id
    private Long id;
    private String buyerDni;
    private String sellerDni;
    private  Long javaCoinsAmount;
    private Long javaCoinPrice;
    private OrderState orderState;

    @Override
    public boolean isNew() {
        return sellerDni == null;
    }
}
