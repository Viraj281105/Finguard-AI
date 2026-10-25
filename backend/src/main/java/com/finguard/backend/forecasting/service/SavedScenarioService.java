package com.finguard.backend.forecasting.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finguard.backend.common.error.ApiException;
import com.finguard.backend.forecasting.domain.SavedScenario;
import com.finguard.backend.forecasting.domain.SavedScenarioRepository;
import com.finguard.backend.forecasting.dto.SavedScenarioResponses.Detail;
import com.finguard.backend.forecasting.dto.SavedScenarioResponses.Rerun;
import com.finguard.backend.forecasting.dto.SavedScenarioResponses.Summary;
import com.finguard.backend.forecasting.dto.ScenarioSimulationRequest;
import com.finguard.backend.forecasting.dto.ScenarioSimulationResponse;
import com.finguard.backend.identity.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Keeps scenarios the user wants to come back to. Saving runs the normal (validated) simulation once and stores the
 * request plus that result; nothing else is written. Everything is owner-scoped: someone else's id is a 404.
 */
@Service
@RequiredArgsConstructor
public class SavedScenarioService {

    public static final int MAX_SAVED_PER_USER = 50;

    private final SavedScenarioRepository repository;
    private final ForecastingService forecastingService;
    private final ObjectMapper mapper;

    static String modelVersion() {
        return ForecastMethod.NAME + " " + ForecastMethod.VERSION;
    }

    @Transactional
    public Detail save(User user, ScenarioSimulationRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw ApiException.badRequest("A saved scenario needs a name.");
        }
        if (repository.countByUserId(user.getId()) >= MAX_SAVED_PER_USER) {
            throw ApiException.conflict("You can keep at most " + MAX_SAVED_PER_USER + " saved scenarios. Delete one first.");
        }
        ScenarioSimulationResponse result = forecastingService.simulateScenario(user, request);
        if (!result.available()) {
            throw ApiException.badRequest("This scenario cannot be saved: " + result.unavailableReason());
        }
        SavedScenario entity = new SavedScenario();
        entity.setUser(user);
        entity.setName(request.name().trim());
        entity.setHorizonMonths(result.horizonMonths());
        entity.setRequestJson(write(request));
        entity.setResultJson(write(result));
        entity.setMethodVersion(modelVersion());
        SavedScenario saved = repository.save(entity);
        return new Detail(summary(saved, result), request, result);
    }

    @Transactional(readOnly = true)
    public List<Summary> list(User user) {
        return repository.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(s -> summary(s, read(s.getResultJson(), ScenarioSimulationResponse.class)))
                .toList();
    }

    @Transactional(readOnly = true)
    public Detail get(User user, String id) {
        SavedScenario s = owned(user, id);
        ScenarioSimulationResponse snapshot = read(s.getResultJson(), ScenarioSimulationResponse.class);
        return new Detail(summary(s, snapshot), read(s.getRequestJson(), ScenarioSimulationRequest.class), snapshot);
    }

    /** Recalculates on the current ledger. Never overwrites the stored snapshot. */
    @Transactional(readOnly = true)
    public Rerun rerun(User user, String id) {
        SavedScenario s = owned(user, id);
        ScenarioSimulationRequest request = read(s.getRequestJson(), ScenarioSimulationRequest.class);
        ScenarioSimulationResponse snapshot = read(s.getResultJson(), ScenarioSimulationResponse.class);
        ScenarioSimulationResponse current = forecastingService.simulateScenario(user, request);
        boolean versionChanged = !modelVersion().equals(s.getMethodVersion());
        boolean unchanged = !versionChanged && write(current).equals(s.getResultJson());
        return new Rerun(summary(s, snapshot), request, snapshot, current, unchanged, versionChanged, modelVersion());
    }

    @Transactional
    public void delete(User user, String id) {
        repository.delete(owned(user, id));
    }

    private SavedScenario owned(User user, String id) {
        return repository.findByIdAndUserId(id, user.getId()).orElseThrow(() -> ApiException.notFound("Saved scenario not found"));
    }

    private static Summary summary(SavedScenario s, ScenarioSimulationResponse snapshot) {
        return new Summary(s.getId(), s.getName(), s.getHorizonMonths(), s.getMethodVersion(), s.getCreatedAt(),
                snapshot.delta() == null ? null : snapshot.delta().net(),
                snapshot.delta() == null ? null : snapshot.delta().expenses());
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise scenario", e);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored scenario could not be read", e);
        }
    }
}
