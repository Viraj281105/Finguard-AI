package com.finguard.backend.ledger.loans;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LoanRepository extends JpaRepository<Loan, String> {

    List<Loan> findByUserIdOrderByCreatedAtDesc(String userId);

    Optional<Loan> findByIdAndUserId(String id, String userId);
}
