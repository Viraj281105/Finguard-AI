package com.finguard.backend.controller;

import com.finguard.backend.dto.InvestmentDTO;
import com.finguard.backend.model.Investment;
import com.finguard.backend.model.User;
import com.finguard.backend.service.InvestmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/investments")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:5173")
public class InvestmentController {

    private final InvestmentService investmentService;

    @PostMapping
    public ResponseEntity<Investment> create(@Valid @RequestBody InvestmentDTO dto,
                                              @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(investmentService.create(dto, user));
    }

    @GetMapping
    public ResponseEntity<List<Investment>> getAll(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(investmentService.getAll(user));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Investment> getById(@PathVariable String id) {
        return ResponseEntity.ok(investmentService.getById(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Investment> update(@PathVariable String id,
                                              @Valid @RequestBody InvestmentDTO dto) {
        return ResponseEntity.ok(investmentService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        investmentService.delete(id);
        return ResponseEntity.noContent().build();
    }
}