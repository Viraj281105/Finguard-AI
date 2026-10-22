package com.finguard.backend.ingestion.web;

import com.finguard.backend.common.error.ApiException;
import com.finguard.backend.identity.domain.User;
import com.finguard.backend.ingestion.service.ImportService;
import com.finguard.backend.ingestion.service.UploadJob;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Locale;

/**
 * {@code POST /api/upload/csv} starts an asynchronous import and answers 202 with the job;
 * {@code GET /api/upload/status/{jobId}} reports progress (the frontend polls it);
 * {@code GET /api/upload/jobs} returns the user's import history.
 */
@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
public class UploadController {

    private final ImportService importService;

    public static final long MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024; // 5 MB

    @PostMapping("/csv")
    public ResponseEntity<UploadStatusResponse> uploadCsv(@RequestParam("file") MultipartFile file,
                                                          @AuthenticationPrincipal User user) {
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new org.springframework.web.multipart.MaxUploadSizeExceededException(MAX_FILE_SIZE_BYTES);
        }
        if (file.isEmpty()) {
            throw ApiException.badRequest("The uploaded file is empty");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw ApiException.badRequest("Only .csv files are supported");
        }
        return ResponseEntity.accepted().body(UploadStatusResponse.from(importService.initiateUpload(file, user)));
    }

    @GetMapping("/status/{jobId}")
    public ResponseEntity<UploadStatusResponse> getStatus(@PathVariable String jobId,
                                                          @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(UploadStatusResponse.from(importService.getJob(jobId, user)));
    }

    @GetMapping("/jobs")
    public ResponseEntity<List<UploadStatusResponse>> getJobs(@AuthenticationPrincipal User user) {
        List<UploadJob> userJobs = importService.getJobsForUser(user);
        return ResponseEntity.ok(userJobs.stream().map(UploadStatusResponse::from).toList());
    }
}
