package com.finguard.backend.forecasting.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SavedScenarioRepository extends JpaRepository<SavedScenario, String> {

    List<SavedScenario> findByUserIdOrderByCreatedAtDesc(String userId);

    Optional<SavedScenario> findByIdAndUserId(String id, String userId);

    long countByUserId(String userId);
}
