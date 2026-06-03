package com.mytext.learningassistant.auth;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mytext.learningassistant.common.BusinessException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

@Service
@EnableConfigurationProperties(EmailCodeProperties.class)
public class EmailCodeService {

    private static final Logger log = LoggerFactory.getLogger(EmailCodeService.class);

    private final EmailCodeProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, CodeEntry> codes = new ConcurrentHashMap<>();
    private final Map<String, Instant> emailLastSentAt = new ConcurrentHashMap<>();
    private final Map<String, IpBucket> ipBuckets = new ConcurrentHashMap<>();

    public EmailCodeService(EmailCodeProperties properties) {
        this.properties = properties;
    }

    public void sendCode(String rawEmail, String provider, String ipAddress) {
        String email = normalizeEmail(rawEmail);
        Instant now = Instant.now();
        cleanup(now);
        checkEmailCooldown(email, now);
        checkIpLimit(ipAddress, now);

        String code = "%06d".formatted(secureRandom.nextInt(1_000_000));
        if (properties.isEnabled()) {
            sendMail(email, code, provider);
        } else if (properties.isLogCodeWhenMailDisabled()) {
            log.info("Email login code for {} is {}. Enable app.email-code.enabled to send real mail.", email, code);
        } else {
            throw new BusinessException(500, "邮箱验证码服务未开启");
        }

        codes.put(email, new CodeEntry(code, now.plusSeconds(properties.getTtlSeconds())));
        emailLastSentAt.put(email, now);
    }

    public boolean verify(String rawEmail, String submittedCode) {
        String email = normalizeEmail(rawEmail);
        CodeEntry entry = codes.get(email);
        if (entry == null || entry.expiresAt().isBefore(Instant.now()) || !entry.code().equals(submittedCode)) {
            return false;
        }
        codes.remove(email);
        return true;
    }

    private void sendMail(String email, String code, String provider) {
        List<ProviderAccount> accounts = accountsFor(provider);
        MailException lastFailure = null;
        for (ProviderAccount providerAccount : accounts) {
            try {
                sendMailWithAccount(email, code, providerAccount.account());
                if (!providerAccount.provider().equals(normalizeProvider(provider))) {
                    log.info("Sent email login code to {} through fallback provider {}", email, providerAccount.provider());
                }
                return;
            } catch (MailException exception) {
                lastFailure = exception;
                log.warn(
                    "Failed to send email login code to {} through provider {} using {}:{}",
                    email,
                    providerAccount.provider(),
                    providerAccount.account().getHost(),
                    providerAccount.account().getPort(),
                    exception
                );
            }
        }
        throw new BusinessException(500, "验证码发送失败，请稍后再试");
    }

    private void sendMailWithAccount(String email, String code, EmailCodeProperties.SmtpAccount account) {
        JavaMailSenderImpl mailSender = mailSender(account);
        SimpleMailMessage message = new SimpleMailMessage();
        String from = firstNonBlank(account.getFrom(), properties.getFrom(), account.getUsername());
        if (!from.isBlank()) {
            message.setFrom(from);
        }
        message.setTo(email);
        message.setSubject(properties.getSubject());
        message.setText("您的登录验证码是：" + code + "，5 分钟内有效。请勿泄露给他人。");
        mailSender.send(message);
    }

    private List<ProviderAccount> accountsFor(String provider) {
        String normalizedProvider = normalizeProvider(provider);
        List<ProviderAccount> accounts = new ArrayList<>();
        addAccount(accounts, normalizedProvider);
        if (!"qq".equals(normalizedProvider)) {
            addAccount(accounts, "qq");
        }
        if (!"netease".equals(normalizedProvider) && !"163".equals(normalizedProvider)) {
            addAccount(accounts, "netease");
        }
        if (accounts.isEmpty()) {
            throw new BusinessException(500, "邮箱验证码服务未配置");
        }
        return accounts;
    }

    private void addAccount(List<ProviderAccount> accounts, String provider) {
        EmailCodeProperties.SmtpAccount account = switch (provider) {
            case "netease", "163" -> properties.getNetease();
            case "qq" -> properties.getQq();
            default -> throw new BusinessException(400, "验证码通道不正确");
        };
        if (account != null && account.isConfigured()) {
            accounts.add(new ProviderAccount(provider, account));
        }
    }

    private JavaMailSenderImpl mailSender(EmailCodeProperties.SmtpAccount account) {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(account.getHost());
        mailSender.setPort(account.getPort());
        mailSender.setUsername(account.getUsername());
        mailSender.setPassword(account.getPassword());
        mailSender.getJavaMailProperties().put("mail.smtp.auth", String.valueOf(account.isAuth()));
        mailSender.getJavaMailProperties().put("mail.smtp.ssl.enable", String.valueOf(account.isSslEnable()));
        mailSender.getJavaMailProperties().put("mail.smtp.starttls.enable", String.valueOf(account.isStarttlsEnable()));
        mailSender.getJavaMailProperties().put("mail.smtp.connectiontimeout", String.valueOf(properties.getConnectTimeoutMillis()));
        mailSender.getJavaMailProperties().put("mail.smtp.timeout", String.valueOf(properties.getTimeoutMillis()));
        mailSender.getJavaMailProperties().put("mail.smtp.writetimeout", String.valueOf(properties.getWriteTimeoutMillis()));
        return mailSender;
    }

    private void checkEmailCooldown(String email, Instant now) {
        Instant lastSentAt = emailLastSentAt.get(email);
        if (lastSentAt != null && lastSentAt.plusSeconds(properties.getEmailCooldownSeconds()).isAfter(now)) {
            throw new BusinessException(429, "验证码请求过于频繁，请稍后再试");
        }
    }

    private void checkIpLimit(String ipAddress, Instant now) {
        IpBucket bucket = ipBuckets.compute(ipAddress, (key, current) -> {
            if (current == null || current.windowStart().plusSeconds(3600).isBefore(now)) {
                return new IpBucket(now, 1);
            }
            return new IpBucket(current.windowStart(), current.count() + 1);
        });
        if (bucket.count() > properties.getIpHourlyLimit()) {
            throw new BusinessException(429, "验证码请求过于频繁，请稍后再试");
        }
    }

    private void cleanup(Instant now) {
        codes.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        Iterator<Map.Entry<String, IpBucket>> iterator = ipBuckets.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, IpBucket> entry = iterator.next();
            if (entry.getValue().windowStart().plusSeconds(3600).isBefore(now)) {
                iterator.remove();
            }
        }
    }

    private String normalizeEmail(String rawEmail) {
        return rawEmail == null ? "" : rawEmail.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeProvider(String provider) {
        String selected = provider == null || provider.isBlank() ? properties.getDefaultProvider() : provider;
        return selected == null ? "qq" : selected.trim().toLowerCase(Locale.ROOT);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private record ProviderAccount(String provider, EmailCodeProperties.SmtpAccount account) {
    }

    private record CodeEntry(String code, Instant expiresAt) {
    }

    private record IpBucket(Instant windowStart, int count) {
    }
}
