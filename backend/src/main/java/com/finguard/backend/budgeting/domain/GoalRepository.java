package com.finguard.backend.budgeting.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GoalRepository extends JpaRepository<Goal, String> {
    List<Goal> findByUserIdOrderByCreatedAtDesc(String userId);
    List<Goal> findByUserIdAndStatusOrderByCreatedAtDesc(String userId, GoalStatus status);
    Optional<Goal> findByIdAndUserId(String id, String userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM Goal g WHERE g.id = :id AND g.user.id = :userId")
    Optional<Goal> findByIdAndUserIdForUpdate(@Param("id") String id, @Param("userId") String userId);
}
