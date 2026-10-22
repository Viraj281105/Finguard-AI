package com.finguard.backend.ledger.loans;

import com.finguard.backend.identity.domain.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/loans")
@RequiredArgsConstructor
public class LoanController {

    private final LoanService loanService;

    @PostMapping
    public ResponseEntity<LoanResponse> create(@Valid @RequestBody LoanRequest request,
                                               @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(LoanResponse.from(loanService.create(request, user)));
    }

    @GetMapping
    public ResponseEntity<List<LoanResponse>> getAll(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(loanService.getAll(user).stream().map(LoanResponse::from).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<LoanResponse> getById(@PathVariable String id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(LoanResponse.from(loanService.getById(id, user)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<LoanResponse> update(@PathVariable String id,
                                               @Valid @RequestBody LoanRequest request,
                                               @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(LoanResponse.from(loanService.update(id, request, user)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, @AuthenticationPrincipal User user) {
        loanService.delete(id, user);
        return ResponseEntity.noContent().build();
    }
}
