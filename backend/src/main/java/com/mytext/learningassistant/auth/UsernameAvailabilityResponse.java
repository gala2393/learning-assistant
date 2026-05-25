package com.mytext.learningassistant.auth;

public record UsernameAvailabilityResponse(
    String username,
    boolean available,
    String message
) {
}
