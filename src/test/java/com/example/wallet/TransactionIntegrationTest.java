package com.example.wallet;

import com.example.wallet.dto.TransactionRequest;
import com.example.wallet.entity.Wallet;
import com.example.wallet.exception.DuplicateTransactionException;
import com.example.wallet.exception.InsufficientFundsException;
import com.example.wallet.repository.TransactionRecordRepository;
import com.example.wallet.repository.WalletRepository;
import com.example.wallet.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class TransactionIntegrationTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TransactionRecordRepository transactionRecordRepository;

    private UUID userId;

    @BeforeEach
    void setUp() {
        transactionRecordRepository.deleteAll();
        walletRepository.deleteAll();

        userId = UUID.randomUUID();
        Wallet wallet = new Wallet(userId, new BigDecimal("500.00"));
        walletRepository.save(wallet);
    }

    @Test
    @DisplayName("Processes a single valid debit transaction successfully")
    void testSingleValidDebit() {
        // Arrange
        TransactionRequest request = new TransactionRequest();
        request.setTransactionId(UUID.randomUUID());
        request.setUserId(userId);
        request.setAmount(new BigDecimal("100.00"));
        request.setType("DEBIT");

        // Act
        transactionService.processTransaction(request);

        // Assert
        Wallet updatedWallet = walletRepository.findById(userId).orElseThrow();
        assertEquals(0, new BigDecimal("400.00").compareTo(updatedWallet.getBalance()));
        assertEquals(1, transactionRecordRepository.count());
    }

    @Test
    @DisplayName("Sends 3 identical transactionIDs simultaneously. Ensures the balance is only deducted once.")
    void testIdempotencyConcurrent() throws InterruptedException {
        // Arrange
        int numberOfThreads = 3;
        ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);

        UUID sharedTransactionId = UUID.randomUUID();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger duplicateCount = new AtomicInteger(0);

        // Act
        for (int i = 0; i < numberOfThreads; i++) {
            executorService.submit(() -> {
                try {
                    TransactionRequest request = new TransactionRequest();
                    request.setTransactionId(sharedTransactionId);
                    request.setUserId(userId);
                    request.setAmount(new BigDecimal("100.00"));
                    request.setType("DEBIT");

                    latch.await(); // Wait for the starting gun
                    transactionService.processTransaction(request);
                    successCount.incrementAndGet();
                } catch (DuplicateTransactionException e) {
                    duplicateCount.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        latch.countDown(); // Unleash all 3 threads simultaneously
        doneLatch.await(); // Wait for all to finish

        // Assert
        Wallet updatedWallet = walletRepository.findById(userId).orElseThrow();
        assertEquals(0, new BigDecimal("400.00").compareTo(updatedWallet.getBalance()), "Balance should only be deducted once");
        assertEquals(1, successCount.get(), "Only one transaction should succeed");
        assertEquals(2, duplicateCount.get(), "The other two should throw DuplicateTransactionException");
        assertEquals(1, transactionRecordRepository.count(), "Only one record should be saved");
    }

    @Test
    @DisplayName("Sends 10 concurrent debit requests of 100 for a wallet with a 500 balance. Ensures the final balance is exactly 0 and 5 requests fail with insufficient funds.")
    void testRaceConditionConcurrent() throws InterruptedException {
        // Arrange
        int numberOfThreads = 10;
        ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger insufficientFundsCount = new AtomicInteger(0);

        // Act
        for (int i = 0; i < numberOfThreads; i++) {
            executorService.submit(() -> {
                try {
                    TransactionRequest request = new TransactionRequest();
                    request.setTransactionId(UUID.randomUUID()); // Unique ID per request
                    request.setUserId(userId);
                    request.setAmount(new BigDecimal("100.00"));
                    request.setType("DEBIT");

                    latch.await(); // Wait for the starting gun
                    transactionService.processTransaction(request);
                    successCount.incrementAndGet();
                } catch (InsufficientFundsException e) {
                    insufficientFundsCount.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        latch.countDown(); // Unleash all 10 threads simultaneously
        doneLatch.await(); // Wait for all to finish

        // Assert
        Wallet updatedWallet = walletRepository.findById(userId).orElseThrow();
        assertEquals(0, new BigDecimal("0.00").compareTo(updatedWallet.getBalance()), "Balance should be exactly 0");
        assertEquals(5, successCount.get(), "Exactly 5 transactions should succeed");
        assertEquals(5, insufficientFundsCount.get(), "Exactly 5 transactions should fail with InsufficientFundsException");
        assertEquals(5, transactionRecordRepository.count(), "Exactly 5 records should be saved");
    }
}
