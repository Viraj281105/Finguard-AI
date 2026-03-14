package com.finguard.backend.repository;

import com.finguard.backend.model.Investment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InvestmentRepository extends JpaRepository<Investment, String> {
    List<Investment> findByUserIdOrderByCreatedAtDesc(String userId);
}