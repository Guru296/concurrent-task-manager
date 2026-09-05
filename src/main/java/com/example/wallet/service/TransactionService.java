package com.example.wallet.service;

import com.example.wallet.dto.TransactionRequest;
import com.example.wallet.entity.TransactionRecord;
import com.example.wallet.entity.Wallet;
import com.example.wallet.exception.DuplicateTransactionException;
import com.example.wallet.exception.InsufficientFundsException;
import com.example.wallet.repository.TransactionRecordRepository;
import com.example.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final WalletRepository walletRepository;
    private final TransactionRecordRepository transactionRecordRepository;

    @Transactional
    public void processTransaction(TransactionRequest request) {
        
        // 1. Acquire Pessimistic Lock on the Wallet to serialize concurrent requests for the same user
        Wallet wallet = walletRepository.findLockedByUserId(request.getUserId())
                .orElseThrow(() -> new com.example.wallet.exception.WalletNotFoundException("Wallet not found for userId: " + request.getUserId()));

        // 2. Check Idempotency *after* acquiring the lock
        if (transactionRecordRepository.existsById(request.getTransactionId())) {
            throw new DuplicateTransactionException("Transaction " + request.getTransactionId() + " already processed");
        }

        // 3. Safe Balance Check (we own the lock, no one else can modify this balance right now)
        if (wallet.getBalance().compareTo(request.getAmount()) < 0) {
            throw new InsufficientFundsException("Insufficient funds in wallet");
        }

        // 4. Deduct amount
        wallet.setBalance(wallet.getBalance().subtract(request.getAmount()));

        // 5. Save updated wallet
        walletRepository.save(wallet);

        // 6. Save transaction record to finalize the idempotency key
        TransactionRecord record = new TransactionRecord(
                request.getTransactionId(),
                request.getUserId(),
                request.getAmount(),
                request.getType()
        );
        
        transactionRecordRepository.save(record);
    }
}
