package com.busleiman.qwallet.service;

import com.busleiman.qwallet.dto.OrderRequest;
import com.busleiman.qwallet.dto.WalletRequest;
import com.busleiman.qwallet.model.Order;
import com.busleiman.qwallet.model.OrderState;
import com.busleiman.qwallet.repository.WalletAccountRepository;
import com.busleiman.qwallet.repository.OrderRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Connection;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.QueueSpecification;
import reactor.rabbitmq.Receiver;
import reactor.rabbitmq.Sender;

import java.nio.charset.StandardCharsets;
import java.util.Date;


@Service
@Slf4j
public class OrderService {

    @Autowired
    private WalletAccountRepository walletAccountRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private Mono<Connection> connectionMono;
    private final Receiver receiver;
    private final Sender sender;
    @Autowired
    private final ModelMapper modelMapper;
    private static final String QUEUE = "Francisco";
    private static final String QUEUE2 = "Francisco2";

    private ObjectMapper objectMapper = new ObjectMapper();

    public OrderService(WalletAccountRepository walletAccountRepository, OrderRepository orderRepository,
                        ModelMapper modelMapper, Receiver receiver, Sender sender) {
        this.walletAccountRepository = walletAccountRepository;
        this.orderRepository = orderRepository;
        this.modelMapper = modelMapper;
        this.receiver = receiver;
        this.sender = sender;
    }

    @PostConstruct
    private void init() {
        consume();
    }

    @PreDestroy
    public void close() throws Exception {
        connectionMono.block().close();
    }


    public Disposable consume() {

        return receiver.consumeAutoAck(QUEUE).flatMap(message -> {

            String json = new String(message.getBody(), StandardCharsets.UTF_8);
            WalletRequest walletRequest;

            try {
                walletRequest = objectMapper.readValue(json, WalletRequest.class);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }

            return walletAccountRepository.findById(walletRequest.getBuyerDni())
                    .flatMap(buyerWalletAccount -> {


                        return walletAccountRepository.findById(walletRequest.getSellerDni())
                                .flatMap(sellerWalletAccount -> {

                                    if (sellerWalletAccount.getJavaCoins() < walletRequest.getUsdAmount() / walletRequest.getJavaCoinPrice()) {
                                        return Mono.error(new Exception("No hay saldo suficiente"));
                                    }

                                    Order order = Order.builder()
                                            .javaCoinPrice(walletRequest.getJavaCoinPrice())
                                            .orderState(OrderState.IN_PROGRESS)
                                            .sellerDni(walletRequest.getSellerDni())
                                            .buyerDni(walletRequest.getBuyerDni())
                                            .javaCoinsAmount(walletRequest.getUsdAmount() / walletRequest.getJavaCoinPrice())
                                            .build();
                                    return orderRepository.save(order)
                                            .map(order1 -> {
                                                OrderRequest orderRequest = modelMapper.map(order1, OrderRequest.class);

                                                Flux<OutboundMessage> outbound = outboundMessage(orderRequest);

                                                return sender
                                                        .declareQueue(QueueSpecification.queue(QUEUE2))
                                                        .thenMany(sender.sendWithPublishConfirms(outbound))
                                                        .subscribe();
                                            });
                                }).switchIfEmpty(Mono.error(new Exception("User not found")));
                    }).switchIfEmpty(Mono.error(new Exception("User not found")));
        }).subscribe();
    }


    private Flux<OutboundMessage> outboundMessage(OrderRequest orderRequest) {

        String json1;
        try {
            json1 = objectMapper.writeValueAsString(orderRequest);

            long now = System.currentTimeMillis();
            long expirationTime = now + 3600000;
            String subject = orderRequest.getBuyerDni();

            String jwt = Jwts.builder()
                    .setSubject(subject)
                    .setIssuedAt(new Date(now))
                    .setExpiration(new Date(expirationTime))
                    .signWith(SignatureAlgorithm.HS256, "mySecretKey1239876123456123123123123132131231")
                    .claim("message", json1)
                    .compact();

            return Flux.just(new OutboundMessage(
                    "",
                    QUEUE2,
                    jwt.getBytes()));

        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

}

