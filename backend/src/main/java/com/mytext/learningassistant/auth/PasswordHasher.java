package com.mytext.learningassistant.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.stereotype.Component;

@Component
public class PasswordHasher {

    private final SecureRandom secureRandom = new SecureRandom();

    public String hash(String rawPassword) {
        byte[] salt = new byte[16];
        secureRandom.nextBytes(salt);
        byte[] digest = digest(salt, rawPassword);
        return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(digest);
    }

    public boolean matches(String rawPassword, String storedValue) {
        if (storedValue == null || storedValue.isBlank()) {
            return false;
        }
        String[] parts = storedValue.split(":", 2);
        if (parts.length != 2) {
            return false;
        }
        try {
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] expectedDigest = Base64.getDecoder().decode(parts[1]);
            byte[] actualDigest = digest(salt, rawPassword);
            return MessageDigest.isEqual(expectedDigest, actualDigest);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private byte[] digest(byte[] salt, String rawPassword) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            messageDigest.update(salt);
            return messageDigest.digest(rawPassword.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        }
    }
}
