package com.example.wallet.controller;

import com.example.wallet.dto.TransactionRequest;
import com.example.wallet.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @PostMapping("/process")
    public ResponseEntity<String> processTransaction(@Valid @RequestBody TransactionRequest request) {
        transactionService.processTransaction(request);
        return ResponseEntity.ok("Transaction processed successfully");
    }
}
