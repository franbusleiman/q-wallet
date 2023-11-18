package com.busleiman.qwallet.service;

import com.busleiman.qwallet.dto.OrderConfirmation;
import com.busleiman.qwallet.dto.OrderRequest;
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
        consume().subscribe();
        consume2().subscribe();
    }

    @PreDestroy
    public void close() throws Exception {
        connectionMono.block().close();
    }

    /**
     * Se recibe el mensaje order request por parte del servicio Web.
     * Se chequea si el usuario comprador existe, y si no, se crea.
     * Finalmente se registra la orden, y se envía el mensaje de confirmación al servicio Web.
     */
    public Flux<Void> consume() {

        return receiver.consumeAutoAck(QUEUE_C).flatMap(message -> {

            String json = new String(message.getBody(), StandardCharsets.UTF_8);
            OrderRequest orderRequest;

            try {
                orderRequest = objectMapper.readValue(json, OrderRequest.class);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }

            return walletAccountRepository.findById(orderRequest.getBuyerDni())
                    .switchIfEmpty(Mono.defer(() -> {

                        WalletAccount walletAccount = WalletAccount.builder()
                                .userDNI(orderRequest.getBuyerDni())
                                .javaCoins(0.0)
                                .build();
                        walletAccount.setUserDNI(orderRequest.getBuyerDni());

                        return walletAccountRepository.save(walletAccount);
                    })).flatMap(buyerWalletAccount -> {

                        Order order = Order.builder()
                                .id(orderRequest.getId())
                                .javaCoinPrice(orderRequest.getJavaCoinPrice())
                                .orderState(OrderState.ACCEPTED)
                                .buyerDni(orderRequest.getBuyerDni())
                                .javaCoinsAmount(orderRequest.getUsdAmount() / orderRequest.getJavaCoinPrice())
                                .build();
                        return orderRepository.save(order)
                                .flatMap(order1 -> {
                                    OrderConfirmation orderConfirmation1 = modelMapper.map(order, OrderConfirmation.class);
                                    Flux<OutboundMessage> outbound = outboundMessage(orderConfirmation1, QUEUE_G, QUEUES_EXCHANGE);

                                    return sender.send(outbound);
                                });
                    });
        });
    }

    /**
     * Se recibe el mensaje order confirmation por parte del servicio Web.
     *<p>
     * Se chequea el estado de aceptado o no aceptado.
     * <p>
     * En el caso de que NO se acepto, se registra el estado en la orden y se guarda.
     * <p>
     * En el caso de que SI se acepto, se buscan los usuarios comprador y vendedor, y se hace el descuento de
     * javaCoins de la cuenta vendedor, y se le acreditan al comprador.
     *<p>
     * Ante cualquier error, se envía un mensaje de orden no aceptada al servicio wallet, con la descripción del error.
     */
    public Flux<Void> consume2() {

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

                        if (orderConfirmation.getOrderState() == OrderState.NOT_ACCEPTED) {

                            order.setSellerDni(orderConfirmation.getSellerDni());
                            order.setOrderState(OrderState.NOT_ACCEPTED);
                            return orderRepository.save(order)
                                    .map(order1 -> {
                                        OrderConfirmation orderConfirmation1 = modelMapper.map(order, OrderConfirmation.class);
                                        orderConfirmation1.setErrorDescription(orderConfirmation.getErrorDescription());
                                        return orderConfirmation1;
                                    });

                        } else if (orderConfirmation.getOrderState() == OrderState.ACCEPTED) {

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
                                                                        .map(order1 -> modelMapper.map(order, OrderConfirmation.class));
                                                            });
                                                }).switchIfEmpty(orderConfirmationError(order.getId(),
                                                        orderConfirmation.getSellerDni(), "User not found: " + order.getBuyerDni()));

                                    }).switchIfEmpty(orderConfirmationError(order.getId(),
                                            orderConfirmation.getSellerDni(), "User not found: " + orderConfirmation.getSellerDni()));
                        }
                        return orderConfirmationError(order.getId(),
                                orderConfirmation.getSellerDni(), "Order status known: " + orderConfirmation.getOrderState());

                    }).switchIfEmpty(orderConfirmationError(orderConfirmation.getId(),
                            orderConfirmation.getSellerDni(), "Order not found: " + orderConfirmation.getId()))

                    .flatMap(orderConfirmation1 -> {
                        Flux<OutboundMessage> outbound = outboundMessage(orderConfirmation1, QUEUE_F, QUEUES_EXCHANGE);

                        return sender.send(outbound);
                    });
        });
    }


    /**
     * Facilitador para crear mensajes de error.
     *
     */
    public Mono<OrderConfirmation> orderConfirmationError(Long orderId, String sellerDni, String error) {
        return Mono.just(OrderConfirmation.builder()
                .id(orderId)
                .orderState(OrderState.NOT_ACCEPTED)
                .sellerDni(sellerDni)
                .errorDescription(error)
                .build());
    }

    /**
     * Se crea un mensaje, en el cual se especifica exchange, routing-key y cuerpo del mismo.
     *
     */
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

