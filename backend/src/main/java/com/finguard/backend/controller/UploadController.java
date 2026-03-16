package com.finguard.backend.controller;

import com.finguard.backend.dto.UploadStatusResponse;
import com.finguard.backend.model.UploadJob;
import com.finguard.backend.model.User;
import com.finguard.backend.service.CsvUploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/*
 * UploadController
 * ----------------
 * Exposes two endpoints:
 *
 *   POST /api/upload/csv
 *   → Accepts a CSV file, starts async processing, returns jobId immediately.
 *   → Frontend uses this jobId to poll the status endpoint.
 *
 *   GET /api/upload/status/{jobId}
 *   → Returns the current status of the upload job.
 *   → Frontend calls this every 2 seconds until status = COMPLETED or FAILED.
 *
 * Both endpoints require JWT authentication.
 * @AuthenticationPrincipal User user → Spring Security automatically
 * injects the currently logged-in user from the JWT token.
 * Viraj's JwtAuthFilter already handles this — we just receive the User object.
 */
@Slf4j
@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
public class UploadController {

    private final CsvUploadService csvUploadService;

    /*
     * POST /api/upload/csv
     * --------------------
     * Accepts multipart/form-data with a "file" field containing the CSV.
     *
     * Request:
     *   Content-Type: multipart/form-data
     *   file: [the CSV file]
     *
     * Response (202 Accepted — processing started but not done yet):
     *   {
     *     "jobId": "3f9d4a9e-...",
     *     "status": "PENDING",
     *     "bankName": "HDFC",
     *     "originalFilename": "HDFC_March2026.csv",
     *     ...
     *   }
     *
     * Why 202 and not 200?
     * HTTP 202 Accepted means "we received your request and started
     * processing, but it's not done yet." This is semantically correct
     * for async operations — more honest than 200 OK.
     *
     * @param file  the uploaded CSV file (from multipart form)
     * @param user  the authenticated user (injected from JWT by Spring Security)
     */
    @PostMapping("/csv")
    public ResponseEntity<UploadStatusResponse> uploadCsv(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal User user) {

        // Basic validation
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        // Only accept CSV files
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".csv")) {
            return ResponseEntity.badRequest().build();
        }

        try {
            log.info("Received CSV upload from user [{}]: {}", user.getEmail(), filename);
            UploadJob job = csvUploadService.initiateUpload(file, user);

            // Return 202 Accepted with job details
            // Frontend will use the jobId to start polling
            return ResponseEntity.accepted()
                    .body(UploadStatusResponse.from(job));

        } catch (IOException e) {
            log.error("Failed to read uploaded file: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /*
     * GET /api/upload/status/{jobId}
     * --------------------------------
     * Returns the current status of an upload job.
     * Frontend polls this endpoint every 2 seconds after uploading.
     *
     * Response (200 OK):
     *   {
     *     "jobId": "3f9d4a9e-...",
     *     "status": "PROCESSING",   ← or COMPLETED / FAILED
     *     "totalRows": 120,
     *     "rowsProcessed": 0,
     *     ...
     *   }
     *
     * Response (404 Not Found):
     *   If jobId doesn't exist OR belongs to a different user.
     *   (Security: user A cannot check status of user B's job)
     *
     * @param jobId  the UUID returned by POST /api/upload/csv
     * @param user   the authenticated user (injected from JWT)
     */
    @GetMapping("/status/{jobId}")
    public ResponseEntity<UploadStatusResponse> getStatus(
            @PathVariable String jobId,
            @AuthenticationPrincipal User user) {

        try {
            UploadJob job = csvUploadService.getJobStatus(jobId, user.getId());
            return ResponseEntity.ok(UploadStatusResponse.from(job));
        } catch (RuntimeException e) {
            // Job not found or doesn't belong to this user
            log.warn("Job not found [{}] for user [{}]", jobId, user.getEmail());
            return ResponseEntity.notFound().build();
        }
    }
}