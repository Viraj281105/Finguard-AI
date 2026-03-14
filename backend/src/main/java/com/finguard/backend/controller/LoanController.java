package com.finguard.backend.controller;

import com.finguard.backend.dto.LoanDTO;
import com.finguard.backend.model.Loan;
import com.finguard.backend.model.User;
import com.finguard.backend.service.LoanService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/loans")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:5173")
public class LoanController {

    private final LoanService loanService;

    @PostMapping
    public ResponseEntity<Loan> create(@Valid @RequestBody LoanDTO dto,
                                        @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(loanService.create(dto, user));
    }

    @GetMapping
    public ResponseEntity<List<Loan>> getAll(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(loanService.getAll(user));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Loan> getById(@PathVariable String id) {
        return ResponseEntity.ok(loanService.getById(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Loan> update(@PathVariable String id,
                                        @Valid @RequestBody LoanDTO dto) {
        return ResponseEntity.ok(loanService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        loanService.delete(id);
        return ResponseEntity.noContent().build();
    }
}