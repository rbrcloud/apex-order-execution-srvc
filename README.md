# Apex Order Execution Service

This service consumes order-placed events published by **apex-order-srvc**, persists them, validates and executes the orders, then publishes order-executed events. It is part of the Apex trading platform and acts as the execution layer between order placement and downstream processing (e.g. portfolio updates, notifications).

---

## Overview

- **Consumes**: `OrderPlacedEvent` from Kafka topic `order.placed.event` (published by apex-order-srvc). Events are deserialized using a type mapping so the listener receives strongly-typed DTOs.
- **Executes**: Each consumed order is created or updated in the database with status `SUBMITTED`, then validated (e.g. funds, market price — validation logic is currently a stub and can be extended). On successful validation, the order status is set to `EXECUTED` and persisted.
- **Publishes**: `OrderExecutedEvent` to Kafka topic `order.executed.event` with the message key set to the order’s ticker. Downstream services can subscribe to this topic for further processing.

---

## Tech Stack

| Component        | Technology |
|-----------------|------------|
| Language        | **Java 21** |
| Framework       | **Spring Boot 3.4.2** (Web, Data JPA, Kafka) |
| Database        | **PostgreSQL** (persistence in schema `orderexecution`) |
| Migrations      | **Flyway** (with baseline-on-migrate; schema `orderexecution`) |
| Messaging       | **Apache Kafka** (consumer for order-placed, producer for order-executed) |
| Connection pool | **HikariCP** (via Spring Data JPA) |
| Utilities       | **Lombok** |

Shared types (e.g. `OrderPlacedEvent`, enums such as `OrderStatus`, `OrderSide`) come from the **apex-shared-lib** dependency (JitPack).

---

## Prerequisites

- **Java 21+**
- **Maven 3.6+**
- **PostgreSQL**: Database must be reachable; schema `orderexecution` is used (Flyway can create it if configured, or use JPA `ddl-auto: update` for development).
- **Kafka**: Bootstrap server must be reachable at the configured host/port (e.g. `apex-kafka:9092`).

For local development you can use Docker Compose or run PostgreSQL and Kafka manually. Unit tests use an in-memory H2 database and disable Kafka, so no external services are required for `./mvnw test`.

---

## Configuration

Key settings are in `src/main/resources/application.yml`. The application expects environment variables for database connectivity.

### Environment variables

| Variable      | Description |
|---------------|-------------|
| `DB_HOST`     | PostgreSQL host and port (e.g. `localhost:5432`) and optionally path (e.g. `/apex_tracker`). |
| `DB_NAME`     | Database name (used in JDBC URL when not embedded in `DB_HOST`). |
| `DB_USERNAME` | Database user. |
| `DB_PASSWORD` | Database password. |

The JDBC URL is built as: `jdbc:postgresql://${DB_HOST}/${DB_NAME}?currentSchema=orderexecution&sslmode=require&channel_binding=require`. Adjust or override for local dev (e.g. without SSL).

### Application properties

| Property | Description | Default / Note |
|----------|-------------|----------------|
| `spring.datasource.url` | PostgreSQL JDBC URL | Built from `DB_HOST`, `DB_NAME` with `currentSchema=orderexecution` and SSL options. |
| `spring.datasource.username` / `password` | DB credentials | From `DB_USERNAME`, `DB_PASSWORD`. |
| `spring.datasource.hikari.maximum-pool-size` | HikariCP max connections | `5`. |
| `spring.datasource.hikari.connection-timeout` | Connection timeout (ms) | `30000`. |
| `spring.flyway.enabled` | Enable Flyway migrations | `true`. |
| `spring.flyway.default-schema` | Flyway schema | `orderexecution`. |
| `spring.flyway.baseline-on-migrate` | Baseline existing DB | `true`. |
| `spring.jpa.hibernate.ddl-auto` | JPA schema management | `update` (create/update tables from entities). |
| `spring.jpa.properties.hibernate.default_schema` | Hibernate default schema | `orderexecution`. |
| `spring.kafka.bootstrap-servers` | Kafka brokers | `apex-kafka:9092`. |
| `spring.kafka.consumer.group-id` | Consumer group for order-placed | `execution-group`. |
| `spring.kafka.consumer.properties.spring.json.trusted.packages` | Trusted packages for JSON deserialization | `com.rbrcloud.ordersrvc.dto`. |
| `spring.kafka.consumer.properties.spring.json.type.mapping` | Type mapping for `OrderPlacedEvent` | `orderPlacedEvent:com.rbrcloud.ordersrvc.dto.OrderPlacedEvent`. |
| `server.port` | HTTP server port | `8080`. |
| `market-data-srvc.url` | Market data service base URL | `http://market-data-srvc:8080` (for future use, e.g. price validation). |

Override any of these via environment variables or profile-specific YAML (e.g. `application-dev.yml`).

---

## Build & Run

```bash
# Build (skip tests if no DB/Kafka)
./mvnw clean package

# Run (requires PostgreSQL and Kafka to be available)
./mvnw spring-boot:run
```

The service listens on **port 8080** by default. Ensure `DB_HOST`, `DB_NAME`, `DB_USERNAME`, and `DB_PASSWORD` are set (or override `spring.datasource.*` in a profile).

---

## Event Flow

1. **apex-order-srvc** publishes `OrderPlacedEvent` to the Kafka topic `order.placed.event`.
2. This service consumes the event in the consumer group `execution-group` (see `OrderExecutionService.consumeOrderPlacedEvent`).
3. The order is created or updated in the `orderexecution.order_execution` table with status `SUBMITTED` (new orders get `createdAt`; existing orders are updated from the event).
4. **Validation**: The service runs `validateOrder(order)`. The current implementation is a stub that always returns `true`; you can extend it (e.g. funds check, market price from `market-data-srvc`).
5. **On success**:
   - Order status is set to `EXECUTED`, `updatedAt` is set, and the entity is saved.
   - An `OrderExecutedEvent` is built and published to `order.executed.event` with message **key** = ticker. The producer adds the `__TypeId__` header (`orderExecutedEvent`) so consumers can deserialize via type mapping.
6. Downstream services can consume `order.executed.event` for further processing (e.g. portfolio, notifications).

---

## Project Structure

```
src/main/java/com/rbrcloud/orderexecution/
├── OrderExecutionSrvcApplication.java   # Spring Boot entry point
├── config/
│   ├── KafkaConsumerConfig.java         # Kafka consumer factory (OrderPlacedEvent deserialization, group-id)
│   └── KafkaProducerConfig.java         # Kafka producer (JsonSerializer, type mapping for OrderExecutedEvent; active when profile != test)
├── dto/
│   └── OrderExecutedEvent.java          # Outgoing event payload (orderId, userId, ticker, quantity, price, orderSide, executedAt)
├── entity/
│   └── Order.java                       # JPA entity for table orderexecution.order_execution (id, userId, orderSide, ticker, quantity, price, status, createdAt, updatedAt)
├── repository/
│   └── OrderRepository.java             # JpaRepository for Order
└── service/
    ├── OrderExecutionService.java       # Kafka listener for order.placed.event; persist, validate, execute, publish OrderExecutedEvent
    └── KafkaProducerService.java       # Generic Kafka producer helper (sendMessage by topic and message)
```

Note: `OrderPlacedEvent` and enums (`OrderStatus`, `OrderSide`) are provided by **apex-shared-lib**. The producer type mapping for `OrderExecutedEvent` is `orderExecutedEvent:com.rbrcloud.orderexecution.dto.OrderExecutedEvent`.

---

## Dependencies on Other Services

| Dependency | Role |
|------------|------|
| **apex-order-srvc** | Publishes `OrderPlacedEvent` to `order.placed.event`. This service consumes those events. Shared DTOs and enums are used via **apex-shared-lib**. |
| **PostgreSQL** | Persists order execution state in schema `orderexecution`. |
| **Kafka** | Ingestion from `order.placed.event` and publishing to `order.executed.event`. |
| **market-data-srvc** | Base URL is configured for future use (e.g. price or liquidity validation). |

---

## Tests

Unit tests use an in-memory **H2** database (PostgreSQL compatibility mode) and **exclude Kafka auto-configuration**, so no external PostgreSQL or Kafka is required:

```bash
./mvnw test
```

- Test configuration: `src/test/resources/application-test.yml` (H2, `ddl-auto: create-drop`, Kafka excluded, `init-schema.sql` for schema creation).
- Schema init: `src/test/resources/init-schema.sql` creates the `orderexecution` schema if not present.

---

## License

Internal / RBR Cloud.
