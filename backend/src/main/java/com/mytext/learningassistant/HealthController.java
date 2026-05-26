package com.mytext.learningassistant;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class HealthController {

    @GetMapping("/")
    Map<String, String> root() {
        return Map.of(
            "status", "ok",
            "service", "learning-assistant",
            "message", "backend is running"
        );
    }

    @GetMapping("/api/health")
    Map<String, String> health() {
        return Map.of(
            "status", "ok",
            "service", "learning-assistant"
        );
    }
}
