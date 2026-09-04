package com.example.wallet.repository;

import com.example.wallet.entity.TransactionRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface TransactionRecordRepository extends JpaRepository<TransactionRecord, UUID> {
    // JpaRepository already provides existsById(UUID id) and findById(UUID id)
    // which is all we need to check if a transaction was already processed!
}
