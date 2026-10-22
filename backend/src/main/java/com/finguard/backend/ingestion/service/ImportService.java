package com.finguard.backend.ingestion.service;

import com.finguard.backend.common.error.ApiException;
import com.finguard.backend.identity.domain.User;
import com.finguard.backend.ingestion.parser.StatementParserRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Request-side half of statement import: validates quickly, records an {@link UploadJob}, and hands the
 * heavy work to {@link ImportWorker}. Returns immediately so the HTTP request never waits on parsing or AI.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportService {

    public static final int MAX_STATEMENT_ROWS = 10_000;

    private final UploadJobRepository jobs;
    private final StatementParserRegistry parsers;
    private final ImportWorker worker;

    public UploadJob initiateUpload(MultipartFile file, User user) {
        String csvContent = readAndValidate(file);

        UploadJob job = new UploadJob();
        job.setUser(user);
        job.setOriginalFilename(file.getOriginalFilename());
        job.setBankName(parsers.detectBank(csvContent).name());
        job.setStatus(UploadJob.JobStatus.PENDING);
        jobs.save(job);
        log.info("Created upload job [{}] for user [{}] bank [{}]", job.getId(), user.getId(), job.getBankName());

        try {
            worker.process(job.getId(), csvContent, user.getId());
        } catch (TaskRejectedException e) {
            job.setStatus(UploadJob.JobStatus.FAILED);
            job.setErrorMessage("The import queue is full. Please try again in a moment.");
            jobs.save(job);
            throw ApiException.unavailable(job.getErrorMessage());
        }
        return job;
    }

    /** Looks up a job owned by {@code user}; someone else's job is reported as not found. */
    public UploadJob getJob(String jobId, User user) {
        return jobs.findByIdAndUserId(jobId, user.getId())
                .orElseThrow(() -> ApiException.notFound("Upload job not found"));
    }

    /** Returns all upload jobs owned by {@code user}, sorted newest first. */
    public List<UploadJob> getJobsForUser(User user) {
        return jobs.findByUserIdOrderByCreatedAtDesc(user.getId());
    }

    private static String readAndValidate(MultipartFile file) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded file", e);
        }

        if (bytes.length == 0) {
            return "";
        }

        // Check for null bytes in the first 1KB (reject binary files)
        int scanLimit = Math.min(bytes.length, 1024);
        for (int i = 0; i < scanLimit; i++) {
            if (bytes[i] == 0) {
                throw ApiException.badRequest("The uploaded file appears to be a binary file, not a valid CSV statement.");
            }
        }

        String text;
        try {
            CharsetDecoder utf8Decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            text = utf8Decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            log.info("Uploaded file {} is not strict UTF-8; decoding as Windows-1252", file.getOriginalFilename());
            text = new String(bytes, Charset.forName("windows-1252"));
        }

        // Enforce maximum row count
        int lineCount = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lineCount++;
                if (lineCount > MAX_STATEMENT_ROWS) {
                    throw ApiException.badRequest("The statement exceeds the maximum limit of " + MAX_STATEMENT_ROWS + " rows.");
                }
            }
        }

        return text;
    }
}
