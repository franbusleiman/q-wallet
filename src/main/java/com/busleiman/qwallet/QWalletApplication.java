package com.busleiman.qwallet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

@EnableR2dbcRepositories

@SpringBootApplication
public class QWalletApplication {

	public static void main(String[] args) {
		SpringApplication.run(QWalletApplication.class, args);
	}

}
