package com.finguard.backend.ai;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Builds the {@link RestClient} used to call the AI service, with timeouts so a slow service cannot block threads. */
@Configuration
@RequiredArgsConstructor
public class AiConfig {

    static final String TOKEN_HEADER = "X-Internal-Token";

    private final AiProperties properties;

    @Bean(name = "aiRestClient")
    public RestClient aiRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.connectTimeout().toMillis());
        factory.setReadTimeout((int) properties.readTimeout().toMillis());

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.url())
                .requestFactory(factory);
        if (properties.internalToken() != null && !properties.internalToken().isBlank()) {
            builder.defaultHeader(TOKEN_HEADER, properties.internalToken());
        }
        return builder.build();
    }
}
