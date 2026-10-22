package com.finguard.backend.categorization;

import com.finguard.backend.identity.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class CategorizationController {

    private final CategorizationService categorization;

    /**
     * Asks the AI service again for the caller's transactions that have no category. Safe to repeat;
     * transactions the caller has categorised are never changed.
     */
    @PostMapping("/reclassify")
    public ResponseEntity<ReclassifyResponse> reclassify(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "200") int limit) {
        return ResponseEntity.ok(categorization.reclassify(user.getId(), limit));
    }
}
