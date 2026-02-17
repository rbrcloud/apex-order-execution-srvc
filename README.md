# Apex Order Execution Service

This service consumes order-placed events published by **apex-order-srvc**, persists them, validates and executes the orders, then publishes order-executed events.

## Overview

- **Consumes**: `OrderPlacedEvent` from Kafka topic `order.placed.event` (published by apex-order-srvc).
- **Executes**: Validates the order, updates status to `EXECUTED`, and persists the result in the database.
- **Publishes**: `OrderExecutedEvent` to Kafka topic `order.executed.event` after successful execution.

## Tech Stack

- **Java 21**
- **Spring Boot 3.4.2** (Web, Data JPA, Kafka)
- **PostgreSQL** (persistence, schema `orderexecution`)
- **Apache Kafka** (event consumption and publishing)
- **Lombok**

## Prerequisites

- Java 21+
- Maven 3.6+
- PostgreSQL (with schema `orderexecution` or let JPA create it via `ddl-auto: update`)
- Kafka (bootstrap server reachable by the configured host/port)

## Configuration

Key settings in `src/main/resources/application.yml`:

| Property | Description | Default |
|----------|-------------|---------|
| `spring.datasource.url` | PostgreSQL JDBC URL | `jdbc:postgresql://apex-postgres:5432/apex_tracker?currentSchema=orderexecution` |
| `spring.kafka.bootstrap-servers` | Kafka brokers | `apex-kafka:9092` |
| `spring.kafka.consumer.group-id` | Consumer group for order-placed events | `execution-group` |
| `server.port` | HTTP server port | `8081` |
| `market-data-srvc.url` | Market data service base URL | `http://market-data-srvc:8080` |

Override via environment variables or profile-specific YAML as needed.

## Build & Run

```bash
# Build
./mvnw clean package

# Run (requires PostgreSQL and Kafka)
./mvnw spring-boot:run
```

The service listens on **port 8081** by default.

## Event Flow

1. **apex-order-srvc** publishes `OrderPlacedEvent` to topic `order.placed.event`.
2. This service consumes the event (consumer group `execution-group`).
3. Order is created or updated in the `orderexecution.order_execution` table with status `SUBMITTED`.
4. Order is validated (e.g. funds, market price — validation logic can be extended).
5. On success:
   - Status is set to `EXECUTED` and saved.
   - `OrderExecutedEvent` is published to `order.executed.event` (key: ticker).
6. Downstream services can consume `order.executed.event` for further processing.

## Project Structure

```
src/main/java/com/rbrcloud/orderexecution/
├── OrderExecutionSrvcApplication.java   # Entry point
├── config/
│   ├── KafkaConsumerConfig.java         # Kafka consumer (OrderPlacedEvent)
│   └── KafkaProducerConfig.java         # Kafka producer (OrderExecutedEvent)
├── dto/
│   └── OrderExecutedEvent.java          # Outgoing event payload
├── entity/
│   └── Order.java                       # JPA entity (order_execution table)
├── repository/
│   └── OrderRepository.java             # Order persistence
└── service/
    ├── OrderExecutionService.java       # Listens to order.placed.event, executes, publishes
    └── KafkaProducerService.java       # Generic Kafka producer helper
```

## Dependencies on Other Services

- **apex-order-srvc**: Provides `OrderPlacedEvent` DTO and shared enums (`OrderStatus`, `OrderSide`) via the `apex-order-srvc` library dependency. Publishes to `order.placed.event`.
- **PostgreSQL**: Database for order execution state.
- **Kafka**: Event ingestion and publishing.
- **market-data-srvc** (optional): URL is configured for future use (e.g. price validation).

## Tests

Unit tests use an in-memory H2 database and exclude Kafka auto-configuration so no external services are required:

```bash
./mvnw test
```

Test config: `src/test/resources/application-test.yml`.

## License

Internal / RBR Cloud.
