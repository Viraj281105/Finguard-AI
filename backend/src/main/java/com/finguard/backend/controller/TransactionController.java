package com.finguard.backend.controller;

import com.finguard.backend.dto.TransactionDTO;
import com.finguard.backend.model.Transaction;
import com.finguard.backend.model.User;
import com.finguard.backend.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:5173")
public class TransactionController {

    private final TransactionService transactionService;

    @PostMapping
    public ResponseEntity<Transaction> create(@Valid @RequestBody TransactionDTO dto,
                                               @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(transactionService.create(dto, user));
    }

    @GetMapping
    public ResponseEntity<List<Transaction>> getAll(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(transactionService.getAll(user));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Transaction> getById(@PathVariable String id) {
        return ResponseEntity.ok(transactionService.getById(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Transaction> update(@PathVariable String id,
                                               @Valid @RequestBody TransactionDTO dto) {
        return ResponseEntity.ok(transactionService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        transactionService.delete(id);
        return ResponseEntity.noContent().build();
    }
}