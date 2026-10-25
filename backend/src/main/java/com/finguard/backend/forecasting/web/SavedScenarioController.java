package com.finguard.backend.forecasting.web;

import com.finguard.backend.forecasting.dto.SavedScenarioResponses.Detail;
import com.finguard.backend.forecasting.dto.SavedScenarioResponses.Rerun;
import com.finguard.backend.forecasting.dto.SavedScenarioResponses.Summary;
import com.finguard.backend.forecasting.dto.ScenarioSimulationRequest;
import com.finguard.backend.forecasting.service.SavedScenarioService;
import com.finguard.backend.identity.domain.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Saved what-if scenarios. Calculating without saving stays at POST /api/forecast/scenarios/simulate. */
@RestController
@RequestMapping("/api/scenarios")
@RequiredArgsConstructor
public class SavedScenarioController {

    private final SavedScenarioService service;

    @PostMapping
    public ResponseEntity<Detail> save(@AuthenticationPrincipal User user, @Valid @RequestBody ScenarioSimulationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.save(user, request));
    }

    @GetMapping
    public ResponseEntity<List<Summary>> list(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(service.list(user));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Detail> get(@AuthenticationPrincipal User user, @PathVariable String id) {
        return ResponseEntity.ok(service.get(user, id));
    }

    @PostMapping("/{id}/rerun")
    public ResponseEntity<Rerun> rerun(@AuthenticationPrincipal User user, @PathVariable String id) {
        return ResponseEntity.ok(service.rerun(user, id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal User user, @PathVariable String id) {
        service.delete(user, id);
        return ResponseEntity.noContent().build();
    }
}
