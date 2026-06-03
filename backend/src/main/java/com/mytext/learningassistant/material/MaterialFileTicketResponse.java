package com.mytext.learningassistant.material;

public record MaterialFileTicketResponse(
    String ticket,
    String url,
    long expiresAt
) {
}
