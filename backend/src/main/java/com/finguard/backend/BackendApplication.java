package com.finguard.backend;

import com.finguard.backend.identity.security.SecurityEnvironmentGuard;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BackendApplication {

    public static void main(String[] args) {
        createSpringApplication().run(args);
    }

    public static SpringApplication createSpringApplication() {
        SpringApplication app = new SpringApplication(BackendApplication.class);
        app.addInitializers(new SecurityEnvironmentGuard());
        return app;
    }
}
