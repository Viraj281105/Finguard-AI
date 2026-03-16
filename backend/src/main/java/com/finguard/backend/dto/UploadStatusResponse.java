package com.finguard.backend.dto;

import com.finguard.backend.model.UploadJob;
import lombok.Data;

import java.time.LocalDateTime;

/*
 * UploadStatusResponse
 * --------------------
 * The JSON shape returned by:
 *   POST /api/upload/csv           → returns this with status = PENDING
 *   GET  /api/upload/status/{jobId} → returns this with current status
 *
 * Why a separate DTO instead of returning UploadJob directly?
 * -----------------------------------------------------------
 * UploadJob is a JPA entity with a @ManyToOne User relation.
 * If we return it directly, Jackson would try to serialize the
 * full User object (including the hashed password!) into the JSON.
 * A DTO gives us full control over exactly what the frontend sees.
 *
 * Example response (PROCESSING):
 * {
 *   "jobId": "3f9d4a9e-92f4-4b41-8c4f-45c3a23c7a92",
 *   "status": "PROCESSING",
 *   "bankName": "HDFC",
 *   "originalFilename": "HDFC_March2026.csv",
 *   "totalRows": 120,
 *   "rowsProcessed": 0,
 *   "errorMessage": null,
 *   "createdAt": "2026-03-15T10:30:00"
 * }
 *
 * Example response (COMPLETED):
 * {
 *   "jobId": "3f9d4a9e-...",
 *   "status": "COMPLETED",
 *   "bankName": "HDFC",
 *   "originalFilename": "HDFC_March2026.csv",
 *   "totalRows": 120,
 *   "rowsProcessed": 120,
 *   "errorMessage": null,
 *   "createdAt": "2026-03-15T10:30:00",
 *   "completedAt": "2026-03-15T10:30:45"
 * }
 */
@Data
public class UploadStatusResponse {

    private String jobId;
    private UploadJob.JobStatus status;
    private String bankName;
    private String originalFilename;
    private Integer totalRows;
    private Integer rowsProcessed;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    /*
     * Static factory method — converts an UploadJob entity into this DTO.
     * Usage: UploadStatusResponse.from(job)
     *
     * This is cleaner than a constructor with 9 parameters.
     */
    public static UploadStatusResponse from(UploadJob job) {
        UploadStatusResponse response = new UploadStatusResponse();
        response.setJobId(job.getId());
        response.setStatus(job.getStatus());
        response.setBankName(job.getBankName());
        response.setOriginalFilename(job.getOriginalFilename());
        response.setTotalRows(job.getTotalRows());
        response.setRowsProcessed(job.getRowsProcessed());
        response.setErrorMessage(job.getErrorMessage());
        response.setCreatedAt(job.getCreatedAt());
        response.setCompletedAt(job.getCompletedAt());
        return response;
    }
}