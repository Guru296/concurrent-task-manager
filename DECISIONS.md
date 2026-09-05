# Technical Decision Log

## 1. Concurrency and Race Condition Handling

**Problem:**
When multiple distinct debit requests for the same wallet arrive simultaneously, they can create a Read-Modify-Write race condition. Without concurrency control, multiple transactions may read the same starting balance before another transaction commits, approve debits based on stale data, and produce incorrect balance updates.

There is also a separate idempotency concern where multiple retries with the same `transactionId` must not result in the transaction being processed more than once.

**Solution: Pessimistic Locking (`@Lock(LockModeType.PESSIMISTIC_WRITE)`)**

We used row-level database locking on the `Wallet` entity via Spring Data JPA.

* When a transaction begins, the application fetches the specific user's wallet using a pessimistic write lock.
* The database grants the lock to one transaction, while other concurrent transactions attempting to acquire a conflicting lock or modify the same wallet row must wait.
* Once the first transaction commits, updating the balance and inserting the transaction record, the lock is released.
* The next waiting transaction acquires the lock, reads the freshly updated balance, and proceeds safely.
* This serialization prevents the negative-balance race condition.

The balance is therefore always validated after acquiring the lock. For example, if 10 concurrent requests attempt to debit ₹100 from a wallet containing ₹500, only 5 requests can successfully process the debit. The remaining requests acquire the lock later, observe the updated balance, and fail due to insufficient funds.

Idempotency is handled separately using `transactionId`. Since `transactionId` is the primary key of `TransactionRecord`, the database enforces uniqueness and prevents multiple records with the same transaction ID.

---

## 2. Incorrect / Sub-Optimal AI Suggestion Identified

**The Suggestion:**

The AI originally proposed, and we implemented, handling idempotency by first acquiring the pessimistic lock on the `Wallet` and then checking `transactionRepository.existsById()` to determine whether the webhook was a duplicate.

**Why it is Sub-Optimal for Scale:**

While this approach correctly satisfies the requirements of this assignment and keeps the application logic simple, it can be sub-optimal for a high-throughput production system.

If a payment gateway experiences a network issue and aggressively retries five duplicate webhooks for a transaction that has already been successfully processed, the current implementation acquires the user's `Wallet` lock for each duplicate request before discovering that it is a duplicate.

During these duplicate checks, genuine new transactions for the same wallet may have to wait for the lock unnecessarily. This creates avoidable lock contention when duplicate traffic is high.

**A More Scalable Alternative:**

A higher-throughput architecture could invert the order of operations:

1. Attempt to insert the `TransactionRecord` first, relying on the database primary key constraint (`@Id` on `transactionId`) to detect duplicates.
2. If the insert fails due to a duplicate key, catch the appropriate `DataIntegrityViolationException` and immediately return `409 Conflict`.
3. If the insert succeeds, acquire the `PESSIMISTIC_WRITE` lock on the `Wallet`.
4. Validate the available balance and process the debit.
5. Commit the entire operation atomically.

Because this flow is wrapped in `@Transactional`, if wallet processing fails after the transaction record is inserted, for example due to insufficient funds, the transaction is rolled back. This ensures that the transaction record does not incorrectly remain marked as successfully processed and block future valid retries.

This Fast-Fail approach allows duplicate requests to be rejected by the database before acquiring the highly contended wallet lock, reducing unnecessary lock contention and keeping the wallet available for genuine transactions.

**Why the Current Approach Was Retained:**

The current lock-first approach was retained because it is simpler to understand, easier to reason about, and fully satisfies the requirements of this assignment. The alternative approach introduces additional considerations around persistence exceptions, flush timing, and transaction rollback behavior.

For the scope of this intern assignment, correctness and clarity were prioritized over high-throughput optimization.

### Key Learning

Idempotency protection and resource locking solve different concurrency problems:

* Database uniqueness on `transactionId` protects against duplicate transaction processing.
* Pessimistic locking on `Wallet` protects shared mutable balance state from concurrent updates.

The current implementation prioritizes correctness and simplicity, while separating duplicate detection from resource locking is a potential optimization for higher-throughput systems.
