package com.finguard.backend.ledger.investments;

import com.finguard.backend.identity.domain.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
@RequestMapping("/api/investments")
@RequiredArgsConstructor
public class InvestmentController {

    private final InvestmentService investmentService;

    @PostMapping
    public ResponseEntity<InvestmentResponse> create(@Valid @RequestBody InvestmentRequest request,
                                                     @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(InvestmentResponse.from(investmentService.create(request, user)));
    }

    @GetMapping
    public ResponseEntity<List<InvestmentResponse>> getAll(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(investmentService.getAll(user).stream().map(InvestmentResponse::from).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<InvestmentResponse> getById(@PathVariable String id,
                                                      @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(InvestmentResponse.from(investmentService.getById(id, user)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<InvestmentResponse> update(@PathVariable String id,
                                                     @Valid @RequestBody InvestmentRequest request,
                                                     @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(InvestmentResponse.from(investmentService.update(id, request, user)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, @AuthenticationPrincipal User user) {
        investmentService.delete(id, user);
        return ResponseEntity.noContent().build();
    }
}
