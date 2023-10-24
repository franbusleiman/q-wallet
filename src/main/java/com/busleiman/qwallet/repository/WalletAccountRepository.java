package com.busleiman.qwallet.repository;


import com.busleiman.qwallet.model.WalletAccount;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WalletAccountRepository extends ReactiveCrudRepository<WalletAccount, String> {

}
