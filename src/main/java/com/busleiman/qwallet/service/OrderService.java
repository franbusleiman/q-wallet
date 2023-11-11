package com.busleiman.qwallet.service;

import com.busleiman.qwallet.dto.OrderConfirmation;
import com.busleiman.qwallet.dto.WalletRequest;
import com.busleiman.qwallet.model.Order;
import com.busleiman.qwallet.model.OrderState;
import com.busleiman.qwallet.model.WalletAccount;
import com.busleiman.qwallet.repository.OrderRepository;
import com.busleiman.qwallet.repository.WalletAccountRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Connection;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.Receiver;
import reactor.rabbitmq.Sender;

import java.nio.charset.StandardCharsets;

import static com.busleiman.qwallet.utils.Constants.*;


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

    private ObjectMapper objectMapper = new ObjectMapper();

    public OrderService(WalletAccountRepository walletAccountRepository, OrderRepository orderRepository,
                        ModelMapper modelMapper, Receiver receiver, Sender sender) {
        this.walletAccountRepository = walletAccountRepository;
        this.orderRepository = orderRepository;
        this.modelMapper = modelMapper;
        this.receiver = receiver;
        this.sender = sender;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        consume();
        consume2();
    }

    @PreDestroy
    public void close() throws Exception {
        connectionMono.block().close();
    }


    public Disposable consume() {

        return receiver.consumeAutoAck(QUEUE_C).flatMap(message -> {

            String json = new String(message.getBody(), StandardCharsets.UTF_8);
            WalletRequest walletRequest;

            try {
                walletRequest = objectMapper.readValue(json, WalletRequest.class);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }

            return walletAccountRepository.findById(walletRequest.getBuyerDni())
                    .switchIfEmpty(Mono.defer(() -> {

                        WalletAccount walletAccount = WalletAccount.builder()
                                .userDNI(walletRequest.getBuyerDni())
                                .javaCoins(0L)
                                .build();
                        walletAccount.setUserDNI(walletRequest.getBuyerDni());

                        return walletAccountRepository.save(walletAccount);
                    })).flatMap(buyerWalletAccount -> {

                        Order order = Order.builder()
                                .id(walletRequest.getId())
                                .javaCoinPrice(walletRequest.getJavaCoinPrice())
                                .orderState(OrderState.IN_PROGRESS)
                                .buyerDni(walletRequest.getBuyerDni())
                                .javaCoinsAmount(walletRequest.getUsdAmount() / walletRequest.getJavaCoinPrice())
                                .build();
                        return orderRepository.save(order)
                                .map(order1 -> {
                                    OrderConfirmation orderConfirmation1 = modelMapper.map(order, OrderConfirmation.class);

                                    Flux<OutboundMessage> outbound = outboundMessage(orderConfirmation1, QUEUE_G, QUEUES_EXCHANGE);

                                    return sender.send(outbound)
                                            .subscribe();
                                });
                    });
        }).subscribe();
    }

    public Disposable consume2() {

        return receiver.consumeAutoAck(QUEUE_A).flatMap(message -> {

            String json = new String(message.getBody(), StandardCharsets.UTF_8);
            OrderConfirmation orderConfirmation;

            try {
                orderConfirmation = objectMapper.readValue(json, OrderConfirmation.class);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }

            return orderRepository.findById(orderConfirmation.getId())
                    .flatMap(order -> {

                        if (orderConfirmation.getOrderState().equals("NOT_ACCEPTED")) {
                            order.setOrderState(OrderState.NOT_ACCEPTED);
                            return orderRepository.save(order)
                                    .map(order1 -> {
                                        OrderConfirmation orderConfirmation1 = modelMapper.map(order, OrderConfirmation.class);

                                        Flux<OutboundMessage> outbound = outboundMessage(orderConfirmation1, QUEUE_G, QUEUES_EXCHANGE);

                                        return sender.sendWithPublishConfirms(outbound)
                                                .subscribe();
                                    });

                        } else if (orderConfirmation.getOrderState().equals("ACCEPTED")) {

                            return walletAccountRepository.findById(orderConfirmation.getSellerDni())
                                    .flatMap(sellerWalletAccount -> {

                                        return walletAccountRepository.findById(order.getBuyerDni())
                                                .flatMap(buyerAccount -> {
                                                    sellerWalletAccount.setJavaCoins(sellerWalletAccount.getJavaCoins() - order.getJavaCoinsAmount());

                                                    buyerAccount.setJavaCoins(buyerAccount.getJavaCoins() + order.getJavaCoinsAmount());

                                                    return walletAccountRepository.save(sellerWalletAccount)
                                                            .then(walletAccountRepository.save(buyerAccount))
                                                            .flatMap(voidResult -> {
                                                                order.setSellerDni(orderConfirmation.getSellerDni());
                                                                order.setOrderState(OrderState.ACCEPTED);

                                                                return orderRepository.save(order)
                                                                        .map(order1 -> {
                                                                            OrderConfirmation orderConfirmation1 = modelMapper.map(order, OrderConfirmation.class);

                                                                            Flux<OutboundMessage> outbound = outboundMessage(orderConfirmation1, QUEUE_F, QUEUES_EXCHANGE);

                                                                            return sender.send(outbound)
                                                                                    .subscribe();
                                                                        });
                                                            });
                                                }).switchIfEmpty(Mono.error(new Exception("User not found")));
                                    });
                        }
                        return Mono.error(new Exception("Order Status unknown: " + orderConfirmation.getOrderState()));
                    }).switchIfEmpty(Mono.error(new Exception("Order not found")));
        }).subscribe();
    }


    private Flux<OutboundMessage> outboundMessage(Object message, String routingKey, String exchange) {

        String json;
        try {
            json = objectMapper.writeValueAsString(message);

            return Flux.just(new OutboundMessage(
                    exchange,
                    routingKey,
                    json.getBytes()));

        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}

