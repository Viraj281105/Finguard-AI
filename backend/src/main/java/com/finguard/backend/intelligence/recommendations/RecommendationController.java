package com.finguard.backend.intelligence.recommendations;

import com.finguard.backend.identity.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** General-guidance recommendations for the caller. Read-only; nothing is stored. */
@RestController
@RequestMapping("/api/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;

    @GetMapping
    public ResponseEntity<RecommendationsResponse> getRecommendations(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(recommendationService.getRecommendations(user));
    }
}
