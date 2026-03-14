package com.finguard.backend.repository;

import com.finguard.backend.model.Loan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LoanRepository extends JpaRepository<Loan, String> {
    List<Loan> findByUserIdOrderByCreatedAtDesc(String userId);
}