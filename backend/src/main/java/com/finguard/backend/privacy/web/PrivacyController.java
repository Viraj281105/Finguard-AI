package com.finguard.backend.privacy.web;

import com.finguard.backend.identity.domain.User;
import com.finguard.backend.privacy.dto.AccountDeletionRequest;
import com.finguard.backend.privacy.dto.AccountDeletionResponse;
import com.finguard.backend.privacy.dto.AccountExportResponse;
import com.finguard.backend.privacy.dto.AuditLogEntryDto;
import com.finguard.backend.privacy.dto.PrivacyActionRequest;
import com.finguard.backend.privacy.dto.PrivacyStatusResponse;
import com.finguard.backend.privacy.service.PrivacyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/privacy")
@RequiredArgsConstructor
public class PrivacyController {

    private final PrivacyService privacyService;

    @Value("${jwt.cookie-secure:false}")
    private boolean cookieSecure;

    @GetMapping("/status")
    public ResponseEntity<PrivacyStatusResponse> getStatus(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(privacyService.getStatus(user));
    }

    @PostMapping("/export")
    public ResponseEntity<AccountExportResponse> exportJson(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody PrivacyActionRequest request
    ) {
        return ResponseEntity.ok(privacyService.exportAccountJson(user, request.password()));
    }

    @PostMapping(value = "/export/csv", produces = "text/csv")
    public ResponseEntity<String> exportCsv(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody PrivacyActionRequest request
    ) {
        String csv = privacyService.exportAccountCsv(user, request.password());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"finguard-export.csv\"")
                .body(csv);
    }

    @PostMapping("/delete-request")
    public ResponseEntity<AccountDeletionResponse> requestAccountDeletion(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody AccountDeletionRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        AccountDeletionResponse response = privacyService.requestAccountDeletion(user, request);
        clearRefreshTokenCookie(httpResponse, cookieSecure || httpRequest.isSecure());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/delete-cancel")
    public ResponseEntity<PrivacyStatusResponse> cancelAccountDeletion(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody PrivacyActionRequest request
    ) {
        return ResponseEntity.ok(privacyService.cancelAccountDeletion(user, request.password()));
    }

    @GetMapping("/audit-log")
    public ResponseEntity<List<AuditLogEntryDto>> getAuditLog(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(privacyService.getAuditLog(user));
    }

    private void clearRefreshTokenCookie(HttpServletResponse response, boolean secure) {
        ResponseCookie cookie = ResponseCookie.from("refreshToken", "")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}

