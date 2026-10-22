package com.finguard.backend.ledger.investments;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InvestmentRepository extends JpaRepository<Investment, String> {

    List<Investment> findByUserIdOrderByCreatedAtDesc(String userId);

    Optional<Investment> findByIdAndUserId(String id, String userId);
}
