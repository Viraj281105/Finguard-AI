package com.finguard.backend.service;

import com.finguard.backend.dto.TransactionDTO;
import com.finguard.backend.model.Transaction;
import com.finguard.backend.model.User;
import com.finguard.backend.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;

    public Transaction create(TransactionDTO dto, User user) {
        Transaction t = new Transaction();
        t.setUser(user);
        t.setTitle(dto.getTitle());
        t.setAmount(dto.getAmount());
        t.setType(dto.getType());
        t.setCategory(dto.getCategory());
        t.setNote(dto.getNote());
        return transactionRepository.save(t);
    }

    public List<Transaction> getAll(User user) {
        return transactionRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
    }

    public Transaction getById(String id) {
        return transactionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));
    }

    public Transaction update(String id, TransactionDTO dto) {
        Transaction t = getById(id);
        t.setTitle(dto.getTitle());
        t.setAmount(dto.getAmount());
        t.setType(dto.getType());
        t.setCategory(dto.getCategory());
        t.setNote(dto.getNote());
        return transactionRepository.save(t);
    }

    public void delete(String id) {
        transactionRepository.deleteById(id);
    }
}