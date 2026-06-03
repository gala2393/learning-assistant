package com.mytext.learningassistant.material;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

@Service
public class MaterialFileTicketService {

    private static final long TICKET_TTL_MILLIS = 2 * 60 * 1000L;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    public MaterialFileTicketResponse create(long ownerId, long materialId) {
        cleanupExpired();
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long expiresAt = Instant.now().toEpochMilli() + TICKET_TTL_MILLIS;
        tickets.put(token, new Ticket(ownerId, materialId, expiresAt));
        return new MaterialFileTicketResponse(
            token,
            "/api/materials/" + materialId + "/file?ticket=" + token,
            expiresAt
        );
    }

    public Long consume(String token, long materialId) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Ticket ticket = tickets.remove(token);
        if (ticket == null || ticket.materialId() != materialId || ticket.expiresAt() < Instant.now().toEpochMilli()) {
            return null;
        }
        return ticket.ownerId();
    }

    private void cleanupExpired() {
        long now = Instant.now().toEpochMilli();
        tickets.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
    }

    private record Ticket(long ownerId, long materialId, long expiresAt) {
    }
}
