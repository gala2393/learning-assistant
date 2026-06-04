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

/**
 * 邮箱验证码服务类
 * 负责邮箱验证码的生成、发送、验证和管理
 * 主要功能：
 * 1. 生成6位数字验证码并通过邮件发送
 * 2. 验证用户提交的验证码是否正确
 * 3. 控制发送频率（邮箱冷却时间和IP限制）
 * 4. 支持多个邮件服务商（QQ邮箱、网易邮箱）及故障自动切换
 * 5. 管理验证码的生命周期和过期清理
 */
@Service
@EnableConfigurationProperties(EmailCodeProperties.class)
public class EmailCodeService {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(EmailCodeService.class);

    /** 邮箱验证码配置属性 */
    private final EmailCodeProperties properties;

    /** 安全随机数生成器，用于生成验证码 */
    private final SecureRandom secureRandom = new SecureRandom();

    /** 验证码存储映射，键为邮箱地址，值为验证码条目 */
    private final Map<String, CodeEntry> codes = new ConcurrentHashMap<>();

    /** 邮件最后发送时间映射，用于控制发送频率 */
    private final Map<String, Instant> emailLastSentAt = new ConcurrentHashMap<>();

    /** IP请求桶映射，用于限制每个IP的请求次数 */
    private final Map<String, IpBucket> ipBuckets = new ConcurrentHashMap<>();

    /**
     * 构造函数，注入配置属性
     *
     * @param properties 邮箱验证码配置属性
     */
    public EmailCodeService(EmailCodeProperties properties) {
        this.properties = properties;
    }

    /**
     * 发送验证码到指定邮箱
     * 执行流程：规范化邮箱 -> 清理过期数据 -> 检查邮箱发送冷却 -> 检查IP限制 -> 生成并发送验证码 -> 存储验证码
     *
     * @param rawEmail  原始邮箱地址
     * @param provider  邮件服务商标识（qq、netease、163）
     * @param ipAddress 客户端IP地址，用于频率限制
     * @throws BusinessException 当频率超限或发送失败时抛出业务异常
     */
    public void sendCode(String rawEmail, String provider, String ipAddress) {
        // 规范化邮箱地址（去空格、转小写）
        String email = normalizeEmail(rawEmail);
        Instant now = Instant.now();

        // 清理过期的验证码和IP桶数据
        cleanup(now);

        // 检查邮箱发送冷却时间
        checkEmailCooldown(email, now);

        // 检查IP请求频率限制
        checkIpLimit(ipAddress, now);

        // 生成6位数字验证码（前补零）
        String code = "%06d".formatted(secureRandom.nextInt(1_000_000));

        // 根据配置决定发送方式
        if (properties.isEnabled()) {
            // 启用邮件功能时，实际发送邮件
            sendMail(email, code, provider);
        } else if (properties.isLogCodeWhenMailDisabled()) {
            // 未启用邮件但开启日志时，在日志中输出验证码（开发调试用）
            log.info("Email login code for {} is {}. Enable app.email-code.enabled to send real mail.", email, code);
        } else {
            // 邮件功能未启用且未开启日志，抛出异常
            throw new BusinessException(500, "邮箱验证码服务未开启");
        }

        // 存储验证码及过期时间
        codes.put(email, new CodeEntry(code, now.plusSeconds(properties.getTtlSeconds())));
        // 记录发送时间，用于冷却控制
        emailLastSentAt.put(email, now);
    }

    /**
     * 验证用户提交的验证码是否正确
     * 验证成功后会立即删除已使用的验证码，防止重复使用
     *
     * @param rawEmail      原始邮箱地址
     * @param submittedCode 用户提交的验证码
     * @return true表示验证成功，false表示验证码不存在、已过期或不匹配
     */
    public boolean verify(String rawEmail, String submittedCode) {
        String email = normalizeEmail(rawEmail);
        CodeEntry entry = codes.get(email);

        // 检查验证码是否存在、是否过期、是否匹配
        if (entry == null || entry.expiresAt().isBefore(Instant.now()) || !entry.code().equals(submittedCode)) {
            return false;
        }

        // 验证成功，删除已使用的验证码
        codes.remove(email);
        return true;
    }

    /**
     * 发送邮件验证码
     * 支持故障自动切换：先尝试指定服务商，失败后自动尝试其他已配置的服务商
     *
     * @param email    目标邮箱地址
     * @param code     验证码内容
     * @param provider 首选邮件服务商标识
     * @throws BusinessException 当所有服务商都发送失败时抛出异常
     */
    private void sendMail(String email, String code, String provider) {
        // 获取可用的服务商账户列表（含备选）
        List<ProviderAccount> accounts = accountsFor(provider);
        MailException lastFailure = null;

        // 遍历所有可用账户，尝试发送
        for (ProviderAccount providerAccount : accounts) {
            try {
                sendMailWithAccount(email, code, providerAccount.account());
                // 如果使用的是备选服务商而非用户指定的服务商，记录日志
                if (!providerAccount.provider().equals(normalizeProvider(provider))) {
                    log.info("Sent email login code to {} through fallback provider {}", email, providerAccount.provider());
                }
                return; // 发送成功，直接返回
            } catch (MailException exception) {
                lastFailure = exception;
                // 记录发送失败警告日志
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
        // 所有账户都发送失败，抛出业务异常
        throw new BusinessException(500, "验证码发送失败，请稍后再试");
    }

    /**
     * 使用指定的SMTP账户发送邮件
     *
     * @param email   目标邮箱地址
     * @param code    验证码内容
     * @param account SMTP账户配置
     */
    private void sendMailWithAccount(String email, String code, EmailCodeProperties.SmtpAccount account) {
        // 创建邮件发送器并配置SMTP参数
        JavaMailSenderImpl mailSender = mailSender(account);
        SimpleMailMessage message = new SimpleMailMessage();

        // 设置发件人地址（优先使用账户配置 -> 全局配置 -> 用户名）
        String from = firstNonBlank(account.getFrom(), properties.getFrom(), account.getUsername());
        if (!from.isBlank()) {
            message.setFrom(from);
        }

        message.setTo(email);
        message.setSubject(properties.getSubject());
        message.setText("您的登录验证码是：" + code + "，5 分钟内有效。请勿泄露给他人。");
        mailSender.send(message);
    }

    /**
     * 获取指定服务商的可用账户列表
     * 列表顺序：首选服务商 -> 其他备选服务商
     *
     * @param provider 首选邮件服务商标识
     * @return 可用的账户列表，至少包含一个账户
     * @throws BusinessException 当没有任何服务商配置完整时抛出异常
     */
    private List<ProviderAccount> accountsFor(String provider) {
        String normalizedProvider = normalizeProvider(provider);
        List<ProviderAccount> accounts = new ArrayList<>();

        // 先添加首选服务商
        addAccount(accounts, normalizedProvider);

        // 如果首选不是QQ，添加QQ作为备选
        if (!"qq".equals(normalizedProvider)) {
            addAccount(accounts, "qq");
        }

        // 如果首选不是网易，添加网易作为备选
        if (!"netease".equals(normalizedProvider) && !"163".equals(normalizedProvider)) {
            addAccount(accounts, "netease");
        }

        // 如果没有任何可用账户，抛出异常
        if (accounts.isEmpty()) {
            throw new BusinessException(500, "邮箱验证码服务未配置");
        }

        return accounts;
    }

    /**
     * 将指定服务商的SMTP账户添加到列表中（仅当配置完整时添加）
     *
     * @param accounts 账户列表
     * @param provider 服务商标识
     */
    private void addAccount(List<ProviderAccount> accounts, String provider) {
        // 根据服务商标识获取对应的SMTP账户配置
        EmailCodeProperties.SmtpAccount account = switch (provider) {
            case "netease", "163" -> properties.getNetease();
            case "qq" -> properties.getQq();
            default -> throw new BusinessException(400, "验证码通道不正确");
        };

        // 仅当账户配置完整时才添加到列表
        if (account != null && account.isConfigured()) {
            accounts.add(new ProviderAccount(provider, account));
        }
    }

    /**
     * 创建并配置JavaMailSender实例
     *
     * @param account SMTP账户配置
     * @return 配置好的JavaMailSender实例
     */
    private JavaMailSenderImpl mailSender(EmailCodeProperties.SmtpAccount account) {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(account.getHost());
        mailSender.setPort(account.getPort());
        mailSender.setUsername(account.getUsername());
        mailSender.setPassword(account.getPassword());

        // 配置SMTP属性
        mailSender.getJavaMailProperties().put("mail.smtp.auth", String.valueOf(account.isAuth()));
        mailSender.getJavaMailProperties().put("mail.smtp.ssl.enable", String.valueOf(account.isSslEnable()));
        mailSender.getJavaMailProperties().put("mail.smtp.starttls.enable", String.valueOf(account.isStarttlsEnable()));

        // 配置超时参数
        mailSender.getJavaMailProperties().put("mail.smtp.connectiontimeout", String.valueOf(properties.getConnectTimeoutMillis()));
        mailSender.getJavaMailProperties().put("mail.smtp.timeout", String.valueOf(properties.getTimeoutMillis()));
        mailSender.getJavaMailProperties().put("mail.smtp.writetimeout", String.valueOf(properties.getWriteTimeoutMillis()));

        return mailSender;
    }

    /**
     * 检查邮箱发送冷却时间
     * 同一邮箱在冷却时间内不能重复发送验证码
     *
     * @param email 邮箱地址
     * @param now   当前时间
     * @throws BusinessException 当请求过于频繁时抛出429异常
     */
    private void checkEmailCooldown(String email, Instant now) {
        Instant lastSentAt = emailLastSentAt.get(email);
        if (lastSentAt != null && lastSentAt.plusSeconds(properties.getEmailCooldownSeconds()).isAfter(now)) {
            throw new BusinessException(429, "验证码请求过于频繁，请稍后再试");
        }
    }

    /**
     * 检查IP地址请求频率限制
     * 同一IP在1小时内不能超过配置的最大请求数
     *
     * @param ipAddress 客户端IP地址
     * @param now       当前时间
     * @throws BusinessException 当请求过于频繁时抛出429异常
     */
    private void checkIpLimit(String ipAddress, Instant now) {
        // 原子操作：更新或创建IP桶
        IpBucket bucket = ipBuckets.compute(ipAddress, (key, current) -> {
            // 如果桶不存在或已过期（超过1小时），创建新桶
            if (current == null || current.windowStart().plusSeconds(3600).isBefore(now)) {
                return new IpBucket(now, 1);
            }
            // 否则计数加1
            return new IpBucket(current.windowStart(), current.count() + 1);
        });

        // 检查是否超过限制
        if (bucket.count() > properties.getIpHourlyLimit()) {
            throw new BusinessException(429, "验证码请求过于频繁，请稍后再试");
        }
    }

    /**
     * 清理过期的验证码和IP桶数据
     * 定期清理可防止内存泄漏
     *
     * @param now 当前时间
     */
    private void cleanup(Instant now) {
        // 清理过期的验证码
        codes.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));

        // 清理过期的IP桶（超过1小时）
        Iterator<Map.Entry<String, IpBucket>> iterator = ipBuckets.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, IpBucket> entry = iterator.next();
            if (entry.getValue().windowStart().plusSeconds(3600).isBefore(now)) {
                iterator.remove();
            }
        }
    }

    /**
     * 规范化邮箱地址
     * 去除首尾空格并转换为小写
     *
     * @param rawEmail 原始邮箱地址
     * @return 规范化后的邮箱地址，如果输入为null则返回空字符串
     */
    private String normalizeEmail(String rawEmail) {
        return rawEmail == null ? "" : rawEmail.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 规范化邮件服务商标识
     * 如果未指定或为空，则使用默认服务商
     *
     * @param provider 原始服务商标识
     * @return 规范化后的服务商标识，默认为"qq"
     */
    private String normalizeProvider(String provider) {
        String selected = provider == null || provider.isBlank() ? properties.getDefaultProvider() : provider;
        return selected == null ? "qq" : selected.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 从多个字符串值中返回第一个非空白的值
     *
     * @param values 待检查的字符串数组
     * @return 第一个非空白的字符串，如果都为空则返回空字符串
     */
    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    /**
     * 邮件服务商账户记录类
     * 封装服务商标识和对应的SMTP账户配置
     *
     * @param provider 服务商标识（qq、netease等）
     * @param account  SMTP账户配置
     */
    private record ProviderAccount(String provider, EmailCodeProperties.SmtpAccount account) {
    }

    /**
     * 验证码条目记录类
     * 存储验证码及其过期时间
     *
     * @param code       6位数字验证码
     * @param expiresAt  过期时间点
     */
    private record CodeEntry(String code, Instant expiresAt) {
    }

    /**
     * IP请求桶记录类
     * 用于实现IP级别的请求频率限制
     *
     * @param windowStart 当前时间窗口的起始时间
     * @param count       当前时间窗口内的请求次数
     */
    private record IpBucket(Instant windowStart, int count) {
    }
}
