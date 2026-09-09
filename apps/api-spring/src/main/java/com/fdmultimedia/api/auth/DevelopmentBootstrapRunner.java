package com.fdmultimedia.api.auth;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("dev")
public class DevelopmentBootstrapRunner implements CommandLineRunner {

    private final DevelopmentBootstrapService bootstrapService;

    public DevelopmentBootstrapRunner(DevelopmentBootstrapService bootstrapService) {
        this.bootstrapService = bootstrapService;
    }

    @Override
    public void run(String... args) {
        bootstrapService.bootstrap();
    }
}
