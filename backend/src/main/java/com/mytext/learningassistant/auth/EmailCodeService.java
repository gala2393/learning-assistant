package com.mytext.learningassistant.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mytext.learningassistant.common.BusinessException;
import com.mytext.learningassistant.security.ShortTermStateStore;

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
    private final ShortTermStateStore stateStore;
    private final SecureRandom secureRandom = new SecureRandom();

    public EmailCodeService(EmailCodeProperties properties, ShortTermStateStore stateStore) {
        this.properties = properties;
        this.stateStore = stateStore;
    }

    public void sendCode(String rawEmail, String provider, String ipAddress) {
        String email = normalizeEmail(rawEmail);
        // 先做邮箱冷却和 IP 小时级限制，再生成验证码，避免无效请求消耗随机数和 SMTP 资源。
        checkEmailCooldown(email);
        checkIpLimit(ipAddress);

        String code = "%06d".formatted(secureRandom.nextInt(1_000_000));
        if (properties.isEnabled()) {
            sendMail(email, code, provider);
        } else if (properties.isLogCodeWhenMailDisabled()) {
            log.info("Email login code for {} is {}. Enable app.email-code.enabled to send real mail.", email, code);
        } else {
            throw new BusinessException(500, "邮箱验证码服务未开启");
        }

        stateStore.put(codeKey(email), code, Duration.ofSeconds(Math.max(60, properties.getTtlSeconds())));
        if (properties.getEmailCooldownSeconds() > 0) {
            stateStore.put(
                cooldownKey(email),
                "1",
                Duration.ofSeconds(properties.getEmailCooldownSeconds())
            );
        }
    }

    public boolean verify(String rawEmail, String submittedCode) {
        String email = normalizeEmail(rawEmail);
        String expectedCode = stateStore.get(codeKey(email));
        if (expectedCode == null || submittedCode == null || !expectedCode.equals(submittedCode.trim())) {
            return false;
        }
        // 验证码成功后立即删除，保证同一验证码只能使用一次。
        stateStore.delete(codeKey(email));
        return true;
    }

    private void sendMail(String email, String code, String provider) {
        List<ProviderAccount> accounts = accountsFor(provider);
        for (ProviderAccount providerAccount : accounts) {
            try {
                sendMailWithAccount(email, code, providerAccount.account());
                if (!providerAccount.provider().equals(normalizeProvider(provider))) {
                    log.info("Sent email login code to {} through fallback provider {}", email, providerAccount.provider());
                }
                return;
            } catch (MailException exception) {
                // 当前 SMTP 通道失败时尝试下一个已配置通道，提高验证码送达率。
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
        mailSender.getJavaMailProperties().put(
            "mail.smtp.connectiontimeout",
            String.valueOf(properties.getConnectTimeoutMillis())
        );
        mailSender.getJavaMailProperties().put("mail.smtp.timeout", String.valueOf(properties.getTimeoutMillis()));
        mailSender.getJavaMailProperties().put("mail.smtp.writetimeout", String.valueOf(properties.getWriteTimeoutMillis()));
        return mailSender;
    }

    private void checkEmailCooldown(String email) {
        if (properties.getEmailCooldownSeconds() <= 0) {
            return;
        }
        if (stateStore.get(cooldownKey(email)) != null) {
            throw new BusinessException(429, "验证码请求过于频繁，请稍后再试");
        }
    }

    private void checkIpLimit(String ipAddress) {
        long count = stateStore.incrementAndGet(
            ipKey(ipAddress),
            Duration.ofHours(1).plusMinutes(5)
        );
        if (count > properties.getIpHourlyLimit()) {
            // IP 级限制防止批量枚举邮箱或刷验证码邮件。
            throw new BusinessException(429, "验证码请求过于频繁，请稍后再试");
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

    private String codeKey(String email) {
        return "email:code:" + email;
    }

    private String cooldownKey(String email) {
        return "email:cooldown:" + email;
    }

    private String ipKey(String ipAddress) {
        String normalized = ipAddress == null || ipAddress.isBlank() ? "unknown" : ipAddress.trim();
        return "email:ip-hour:" + normalized;
    }

    private record ProviderAccount(String provider, EmailCodeProperties.SmtpAccount account) {
    }
}
