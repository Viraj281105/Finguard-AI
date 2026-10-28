package com.finguard.backend.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * The application clock. Anything that depends on "today" (forecast horizons, month-to-date budget
 * projections, date validations) takes a {@link Clock} so tests can fix the date instead of depending on the day they run.
 * Defaults to "Asia/Kolkata" for consistent date and month boundaries in Indian retail banking across UTC deployments.
 */
@Configuration
public class ClockConfig {

    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Kolkata");

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock(@Value("${app.timezone:Asia/Kolkata}") String timezone) {
        ZoneId zone = (timezone != null && !timezone.isBlank()) ? ZoneId.of(timezone.trim()) : DEFAULT_ZONE;
        return Clock.system(zone);
    }
}
