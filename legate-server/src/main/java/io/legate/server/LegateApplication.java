package io.legate.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Legate AI Gateway - Main Application.
 *
 * An AI gateway that sits between client applications and LLM providers,
 * providing unified OpenAI-compatible API, intelligent routing, governance, and observability.
 */
@SpringBootApplication
@EnableScheduling
public class LegateApplication {

    static void main(String[] args) {
        SpringApplication.run(LegateApplication.class, args);
    }
}
