package com.example.wallet.service;

import com.example.wallet.dto.TransactionRequest;
import com.example.wallet.entity.TransactionRecord;
import com.example.wallet.entity.Wallet;
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
        
        // --- NOT CONCURRENCY SAFE YET ---
        // 1. Finding wallet without any pessimistic locking
        Wallet wallet = walletRepository.findById(request.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found for userId: " + request.getUserId()));

        // --- NOT CONCURRENCY SAFE YET ---
        // 2. Checking balance in a concurrent environment could lead to a race condition
        if (wallet.getBalance().compareTo(request.getAmount()) < 0) {
            throw new IllegalArgumentException("Insufficient funds in wallet");
        }

        // 3. Deduct amount
        wallet.setBalance(wallet.getBalance().subtract(request.getAmount()));

        // 4. Save updated wallet
        walletRepository.save(wallet);

        // 5. Save transaction record
        TransactionRecord record = new TransactionRecord(
                request.getTransactionId(),
                request.getUserId(),
                request.getAmount(),
                request.getType()
        );
        
        transactionRecordRepository.save(record);
    }
}
