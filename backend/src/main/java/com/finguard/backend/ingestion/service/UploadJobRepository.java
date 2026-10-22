package com.finguard.backend.ingestion.service;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UploadJobRepository extends JpaRepository<UploadJob, String> {

    Optional<UploadJob> findByIdAndUserId(String id, String userId);

    java.util.List<UploadJob> findByUserIdOrderByCreatedAtDesc(String userId);

    java.util.List<UploadJob> findByStatusIn(java.util.Collection<UploadJob.JobStatus> statuses);
}
