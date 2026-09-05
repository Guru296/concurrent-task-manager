# Idempotent Payment/Wallet Event Processor

This project is a Spring Boot application that processes internal transaction ledger events. It is designed to safely handle concurrent webhook payloads and accurately manage wallet balances.

## Problem Statement

- **Duplicate Webhooks:** Payment gateways may retry webhook requests due to network issues. Duplicate transaction requests must not deduct the balance multiple times.
- **Concurrent Debits:** Multiple debit requests for the same wallet may arrive concurrently. Concurrent balance updates must be managed strictly to prevent race conditions and negative balances.

## Features

- Idempotent transaction processing
- Duplicate transaction detection 
- Concurrent debit protection
- Database-level pessimistic locking
- Transactional processing
- Insufficient balance handling
- H2 in-memory database for testing
- Automated integration tests verifying concurrency

## Architecture / Request Flow

The application processes transactions through the following flow:

```text
Client
  ↓
TransactionController
  ↓
TransactionService (@Transactional)
  ↓
Acquire Wallet PESSIMISTIC_WRITE Lock
  ↓
Check Transaction Idempotency (existsById)
  ↓
Validate Balance
  ↓
Update Wallet + Save Transaction Record
  ↓
Commit Transaction
```

## Concurrency Strategy

A normal read-modify-write operation is unsafe because multiple threads can read the same starting balance before any thread commits, leading to lost updates.

This project uses a **`PESSIMISTIC_WRITE`** database lock to protect the wallet balance. When a transaction begins, the database acquires an exclusive row-level lock on that specific user's wallet row. Conflicting concurrent transactions for the same wallet are forced to wait at the database level until the lock is released. This strictly serializes updates and prevents negative balance race conditions.

## Idempotency Strategy

The `transactionId` provided in the payload is used to identify duplicate requests. 

When a transaction request is processed, the application first acquires the wallet's `PESSIMISTIC_WRITE` lock. Inside this lock, it explicitly checks if the transaction already exists using `transactionRecordRepository.existsById(transactionId)`. 
- If it is a duplicate, the application immediately throws a conflict exception and aborts.
- If it is not a duplicate, the application proceeds to validate the balance, update the wallet, and finally save the new `TransactionRecord`.

As an additional safeguard, `transactionId` is the primary key (`@Id`) of the `TransactionRecord` entity, providing strict database-level uniqueness to mathematically guarantee no duplicates are ever persisted.

## API Documentation

### Process Transaction
`POST /api/v1/transactions/process`

**Request Format:**
```json
{
  "transactionId": "UUID",
  "userId": "UUID",
  "amount": 250.00,
  "type": "DEBIT"
}
```

**Field Descriptions:**
- `transactionId` (UUID): Unique identifier for the transaction (used for idempotency).
- `userId` (UUID): The wallet owner's identifier.
- `amount` (BigDecimal): The amount to debit. Must be greater than zero.
- `type` (String): The transaction type.

**Success Response (200 OK):**
```text
Transaction processed successfully
```

**Duplicate Transaction Response (409 Conflict):**
```text
Transaction {id} already processed
```

**Insufficient Balance Response (400 Bad Request):**
```text
Insufficient funds in wallet
```

## Example Request

```bash
curl -X POST http://localhost:8080/api/v1/transactions/process \
  -H "Content-Type: application/json" \
  -d '{
    "transactionId": "550e8400-e29b-41d4-a716-446655440000",
    "userId": "123e4567-e89b-12d3-a456-426614174000",
    "amount": 100.00,
    "type": "DEBIT"
  }'
```

## Testing

This project uses **JUnit 5** and **Spring Boot Integration Testing** against an **H2 in-memory database**. There is zero external database setup required.

The automated test suite verifies the following core scenarios:

1. **Happy Path**
   `"Processes a single valid debit transaction successfully."`
   Verifies standard transaction flow, balance deduction, and record creation.

2. **Idempotency Test**
   `"Sends 3 identical transactionIDs simultaneously. Ensures the balance is only deducted once."`
   Uses an `ExecutorService` and `CountDownLatch` to fire 3 threads simultaneously with the same transaction payload. Verifies that only 1 succeeds and 2 fail as duplicates.

3. **Race Condition Test**
   `"Sends 10 concurrent debit requests of 100 for a wallet with a 500 balance. Ensures the final balance is exactly 0 and 5 requests fail with insufficient funds."`
   Uses an `ExecutorService` and `CountDownLatch` to fire 10 unique transactions against the same wallet simultaneously. Proves the pessimistic lock works by ensuring exactly 5 succeed and exactly 5 fail with insufficient funds.

## Running Tests

To run the entire test suite locally, use the Maven Wrapper:

```bash
./mvnw clean test
```

Expected output:
```text
[INFO] BUILD SUCCESS
```

## Tech Stack

- Java 17
- Spring Boot 4.1.1
- Spring Data JPA
- Hibernate
- H2 Database
- JUnit 5
- Maven

## Project Structure

```text
src/
├── main/
│   ├── java/
│   │   └── com/example/wallet/
│   │       ├── controller/
│   │       ├── dto/
│   │       ├── entity/
│   │       ├── exception/
│   │       ├── repository/
│   │       └── service/
│   └── resources/
└── test/
    └── java/
        └── com/example/wallet/
```

## Engineering Decisions

Detailed architectural and scaling decisions—including the trade-offs of the chosen concurrency and idempotency strategies—are documented in `DECISIONS.md`.

## Important Notes

- Tests run completely isolated using the H2 in-memory database.
- No external database setup is required for running the test suite.
- The concurrency tests are rigorous integration tests designed specifically to trigger and validate race-condition handling.
