package com.finguard.backend.budgeting.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BudgetRepository extends JpaRepository<Budget, String> {
    List<Budget> findByUserIdAndYearMonthOrderByCategoryAsc(String userId, String yearMonth);
    Optional<Budget> findByUserIdAndYearMonthAndCategory(String userId, String yearMonth, String category);
    Optional<Budget> findByUserIdAndYearMonthAndCategoryIsNull(String userId, String yearMonth);
    Optional<Budget> findByIdAndUserId(String id, String userId);
    List<Budget> findByUserIdOrderByYearMonthDescCategoryAsc(String userId);
}
