package com.mytext.learningassistant.auth;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

import javax.imageio.ImageIO;

import com.mytext.learningassistant.common.BusinessException;
import com.mytext.learningassistant.security.ShortTermStateStore;
import com.mytext.learningassistant.user.UserEntity;
import com.mytext.learningassistant.user.UserRole;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class LoginCaptchaService {

    private static final char[] CODE_CHARS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final int WIDTH = 148;
    private static final int HEIGHT = 48;

    private final SecureRandom random = new SecureRandom();
    private final ShortTermStateStore stateStore;
    private final boolean enabled;
    private final int failureThreshold;
    private final Duration ttl;

    public LoginCaptchaService(
        ShortTermStateStore stateStore,
        @Value("${app.security.login-captcha.enabled:true}") boolean enabled,
        @Value("${app.security.login-captcha.failure-threshold:3}") int failureThreshold,
        @Value("${app.security.login-captcha.ttl-seconds:300}") long ttlSeconds
    ) {
        this.stateStore = stateStore;
        this.enabled = enabled;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.ttl = Duration.ofSeconds(Math.max(60L, ttlSeconds));
    }

    public LoginCaptchaResponse createChallenge(String attemptKey) {
        String challengeId = UUID.randomUUID().toString();
        String code = randomCode();
        // challenge 同时绑定 attemptKey 和验证码，避免拿别人的 challenge 复用到当前登录尝试。
        stateStore.put(challengeKey(challengeId), attemptKey + "\n" + code.toLowerCase(Locale.ROOT), ttl);
        return new LoginCaptchaResponse(
            challengeId,
            "data:image/png;base64," + renderCode(code),
            ttl.toSeconds()
        );
    }

    public boolean requiresCaptcha(String attemptKey, UserEntity user) {
        if (!enabled) {
            return false;
        }
        if (user != null && user.getRole() == UserRole.ADMIN) {
            // 管理员账号始终要求验证码，提高高权限入口的防护强度。
            return true;
        }
        return parseLong(stateStore.get(failureKey(attemptKey))) >= failureThreshold;
    }

    public void verifyRequired(String attemptKey, LoginRequest request) {
        if (!enabled) {
            return;
        }
        String challengeId = request.captchaChallengeId() == null ? "" : request.captchaChallengeId().trim();
        String submittedCode = request.captchaCode() == null ? "" : request.captchaCode().trim();
        if (challengeId.isBlank() || submittedCode.isBlank()) {
            throw captchaRequired();
        }

        String expected = attemptKey + "\n" + submittedCode.toLowerCase(Locale.ROOT);
        String actual = stateStore.getAndDelete(challengeKey(challengeId));
        if (!expected.equals(actual)) {
            // getAndDelete 保证即使输错也会消费 challenge，降低暴力试探收益。
            throw captchaRequired();
        }
    }

    public void recordFailure(String attemptKey) {
        if (enabled) {
            stateStore.incrementAndGet(failureKey(attemptKey), Duration.ofMinutes(5));
        }
    }

    public void clearFailures(String attemptKey) {
        stateStore.delete(failureKey(attemptKey));
    }

    private BusinessException captchaRequired() {
        return new BusinessException(428, "请先完成图形验证码");
    }

    private String randomCode() {
        StringBuilder builder = new StringBuilder(4);
        for (int i = 0; i < 4; i += 1) {
            builder.append(CODE_CHARS[random.nextInt(CODE_CHARS.length)]);
        }
        return builder.toString();
    }

    private String renderCode(String code) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(244, 248, 251));
            graphics.fillRect(0, 0, WIDTH, HEIGHT);
            drawNoise(graphics);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
            for (int i = 0; i < code.length(); i += 1) {
                graphics.setColor(new Color(34 + random.nextInt(60), 52 + random.nextInt(55), 70 + random.nextInt(50)));
                double angle = Math.toRadians(random.nextInt(25) - 12);
                graphics.rotate(angle, 26 + i * 30, 31);
                graphics.drawString(String.valueOf(code.charAt(i)), 18 + i * 31, 34 + random.nextInt(5));
                graphics.rotate(-angle, 26 + i * 30, 31);
            }
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (Exception exception) {
            throw new IllegalStateException("captcha render failed", exception);
        }
    }

    private void drawNoise(Graphics2D graphics) {
        graphics.setStroke(new BasicStroke(1.4f));
        for (int i = 0; i < 5; i += 1) {
            graphics.setColor(new Color(150 + random.nextInt(55), 165 + random.nextInt(45), 180 + random.nextInt(35)));
            graphics.drawLine(
                random.nextInt(WIDTH),
                random.nextInt(HEIGHT),
                random.nextInt(WIDTH),
                random.nextInt(HEIGHT)
            );
        }
        for (int i = 0; i < 42; i += 1) {
            graphics.setColor(new Color(170 + random.nextInt(45), 180 + random.nextInt(40), 190 + random.nextInt(35)));
            graphics.fillOval(random.nextInt(WIDTH), random.nextInt(HEIGHT), 2, 2);
        }
    }

    private String challengeKey(String challengeId) {
        return "login:captcha:challenge:" + challengeId;
    }

    private String failureKey(String attemptKey) {
        return "login:captcha:failure:" + attemptKey;
    }

    private long parseLong(String value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }
}
