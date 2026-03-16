package com.finguard.backend.repository;

import com.finguard.backend.model.UploadJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/*
 * UploadJobRepository
 * -------------------
 * Spring Data JPA generates all SQL automatically from method names.
 * We just declare what queries we need.
 */
@Repository
public interface UploadJobRepository extends JpaRepository<UploadJob, String> {

    /*
     * Find a specific job BUT only if it belongs to the given user.
     * This is a security check — user A must not see user B's job status.
     *
     * Returns Optional because the job might not exist,
     * or might belong to a different user.
     */
    Optional<UploadJob> findByIdAndUserId(String id, String userId);

    /*
     * Get all upload jobs for a user, newest first.
     * Used for showing upload history on the dashboard.
     */
    List<UploadJob> findByUserIdOrderByCreatedAtDesc(String userId);
}