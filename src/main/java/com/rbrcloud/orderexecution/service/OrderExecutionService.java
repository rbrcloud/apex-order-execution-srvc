package com.rbrcloud.orderexecution.service;

import com.rbrcloud.orderexecution.dto.OrderExecutedEvent;
import com.rbrcloud.orderexecution.entity.Order;
import com.rbrcloud.orderexecution.repository.OrderRepository;
import com.rbrcloud.ordersrvc.dto.OrderPlacedEvent;
import com.rbrcloud.ordersrvc.enums.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderExecutionService {

    private final OrderRepository orderRepository;

    private static final String ORDER_PLACED_TOPIC = "order.placed.event";

    private static final String ORDER_EXECUTED_TOPIC = "order.executed.event";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Transactional
    @KafkaListener(topics = ORDER_PLACED_TOPIC, groupId = "execution-group")
    public void consumeOrderPlacedEvent(OrderPlacedEvent orderPlacedEvent) {
        log.info("Received OrderPlacedEvent: Order Id: {}, Ticker: {}, Price: {},",
                orderPlacedEvent.getOrderId(), orderPlacedEvent.getTicker(), orderPlacedEvent.getPrice());

        Order order = orderRepository.findById(orderPlacedEvent.getOrderId()).orElse(new Order());

        if (order.getId() == null) {
            order.setId(orderPlacedEvent.getOrderId());
            order.setCreatedAt(LocalDateTime.now());
            order.setUserId(orderPlacedEvent.getUserId());
            order.setTicker(orderPlacedEvent.getTicker());
            order.setQuantity(orderPlacedEvent.getQuantity());
            order.setPrice(orderPlacedEvent.getPrice());
            order.setOrderSide(orderPlacedEvent.getOrderSide());
            order.setStatus(OrderStatus.SUBMITTED);
        }
        orderRepository.save(order);


        // Validate funds, market price and then execute order
        boolean isValidOrder = validateOrder(order);

        if (isValidOrder) {
            // Update DB
            order.setStatus(OrderStatus.EXECUTED);
            order.setUpdatedAt(LocalDateTime.now());
            orderRepository.save(order);

            // Create Order Executed event and publish to kafka
            OrderExecutedEvent event = buildOrderExecutedEvent(order);
            ProducerRecord<String, Object> record = new ProducerRecord<>(ORDER_EXECUTED_TOPIC, event.getTicker(), event);
            record.headers().add("__TypeId__", "orderExecutedEvent".getBytes(StandardCharsets.UTF_8));
            kafkaTemplate.send(record);
            log.info("Published OrderExecutedEvent to Kafka.");
        } else {
            log.error("Validation failed for order {}.", order.getId());
        }
    }

    private boolean validateOrder(Order order) {
        return true;
    }

    private OrderExecutedEvent buildOrderExecutedEvent(Order order) {
        return OrderExecutedEvent.builder()
                .orderId(order.getId())
                .userId(order.getUserId())
                .ticker(order.getTicker())
                .quantity(order.getQuantity())
                .price(order.getPrice())
                .orderSide(order.getOrderSide())
                .executedAt(order.getUpdatedAt()).build();
    }
}
