package com.mytext.learningassistant.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 邮箱验证码配置属性类 — 通过 @ConfigurationProperties 注解绑定 application.yml 中的配置项。
 * <p>
 * 配置前缀为 {@code app.email-code}，用于控制邮箱验证码服务的各项参数，
 * 包括验证码有效期、发送频率限制、SMTP 邮件服务器配置等。
 * <p>
 * 配置示例（application.yml）：
 * <pre>
 * app:
 *   email-code:
 *     enabled: true
 *     ttl-seconds: 300
 *     email-cooldown-seconds: 60
 *     ip-hourly-limit: 10
 *     default-provider: qq
 *     qq:
 *       host: smtp.qq.com
 *       port: 465
 *       username: your-email@qq.com
 *       password: your-auth-code
 * </pre>
 */
@ConfigurationProperties(prefix = "app.email-code")
public class EmailCodeProperties {

    /** 是否启用邮箱验证码功能，默认关闭（开发环境可不开） */
    private boolean enabled = false;

    /** 验证码有效期，单位秒，默认 300 秒（5 分钟）。超过此时间验证码自动失效 */
    private int ttlSeconds = 300;

    /** 同一邮箱发送验证码的冷却时间，单位秒，默认 60 秒。防止用户频繁请求发送 */
    private int emailCooldownSeconds = 60;

    /** 同一 IP 地址每小时最大请求数量限制，默认 10 次。防止恶意刷接口 */
    private int ipHourlyLimit = 10;

    /** SMTP 连接超时时间，单位毫秒，默认 10000 毫秒（10 秒） */
    private int connectTimeoutMillis = 10000;

    /** SMTP 读取超时时间，单位毫秒，默认 10000 毫秒（10 秒） */
    private int timeoutMillis = 10000;

    /** SMTP 写入超时时间，单位毫秒，默认 10000 毫秒（10 秒） */
    private int writeTimeoutMillis = 10000;

    /** 验证码邮件的主题行 */
    private String subject = "智学引擎登录验证码";

    /** 邮件发送者地址，可在各 SMTP 账户中单独配置覆盖 */
    private String from = "";

    /** 默认使用的邮件服务商，可选值：qq、netease */
    private String defaultProvider = "qq";

    /** 当邮件功能未启用时，是否在日志中输出验证码（便于开发调试，生产环境应关闭） */
    private boolean logCodeWhenMailDisabled = true;

    /** QQ 邮箱的 SMTP 账户配置 */
    private SmtpAccount qq = new SmtpAccount();

    /** 网易邮箱的 SMTP 账户配置 */
    private SmtpAccount netease = new SmtpAccount();

    // ========== 以下是 getter/setter 方法，供 Spring 框架自动注入配置值 ==========

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(int ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public int getEmailCooldownSeconds() {
        return emailCooldownSeconds;
    }

    public void setEmailCooldownSeconds(int emailCooldownSeconds) {
        this.emailCooldownSeconds = emailCooldownSeconds;
    }

    public int getIpHourlyLimit() {
        return ipHourlyLimit;
    }

    public void setIpHourlyLimit(int ipHourlyLimit) {
        this.ipHourlyLimit = ipHourlyLimit;
    }

    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public void setConnectTimeoutMillis(int connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    public int getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public int getWriteTimeoutMillis() {
        return writeTimeoutMillis;
    }

    public void setWriteTimeoutMillis(int writeTimeoutMillis) {
        this.writeTimeoutMillis = writeTimeoutMillis;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(String defaultProvider) {
        this.defaultProvider = defaultProvider;
    }

    public boolean isLogCodeWhenMailDisabled() {
        return logCodeWhenMailDisabled;
    }

    public void setLogCodeWhenMailDisabled(boolean logCodeWhenMailDisabled) {
        this.logCodeWhenMailDisabled = logCodeWhenMailDisabled;
    }

    public SmtpAccount getQq() {
        return qq;
    }

    public void setQq(SmtpAccount qq) {
        this.qq = qq;
    }

    public SmtpAccount getNetease() {
        return netease;
    }

    public void setNetease(SmtpAccount netease) {
        this.netease = netease;
    }

    /**
     * SMTP 账户配置内部类 — 封装单个邮件服务商的 SMTP 连接参数。
     * <p>
     * 支持 QQ 邮箱、网易邮箱等常见邮件服务商。
     * 每个服务商需要配置服务器地址、端口、用户名和密码（或授权码）。
     */
    public static class SmtpAccount {

        /** SMTP 服务器地址，如：smtp.qq.com、smtp.163.com */
        private String host = "";

        /** SMTP 服务器端口号，QQ 邮箱 SSL 端口为 465 */
        private int port = 465;

        /** SMTP 登录用户名，通常为完整的邮箱地址 */
        private String username = "";

        /** SMTP 登录密码或授权码（QQ 邮箱需要使用授权码而非密码） */
        private String password = "";

        /** 该账户的发件人地址，可覆盖全局的 from 配置 */
        private String from = "";

        /** 是否需要身份验证，通常为 true */
        private boolean auth = true;

        /** 是否启用 SSL 加密连接，默认开启（推荐） */
        private boolean sslEnable = true;

        /** 是否启用 STARTTLS 加密，默认关闭（SSL 已开启时通常不需要同时开启） */
        private boolean starttlsEnable = false;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }

        public boolean isAuth() {
            return auth;
        }

        public void setAuth(boolean auth) {
            this.auth = auth;
        }

        public boolean isSslEnable() {
            return sslEnable;
        }

        public void setSslEnable(boolean sslEnable) {
            this.sslEnable = sslEnable;
        }

        public boolean isStarttlsEnable() {
            return starttlsEnable;
        }

        public void setStarttlsEnable(boolean starttlsEnable) {
            this.starttlsEnable = starttlsEnable;
        }

        /**
         * 检查该 SMTP 账户是否已完整配置。
         * 必须同时配置主机地址、用户名和密码才视为有效配置。
         *
         * @return true 表示配置完整可用，false 表示配置不完整
         */
        public boolean isConfigured() {
            return !isBlank(host) && !isBlank(username) && !isBlank(password);
        }

        /**
         * 判断字符串是否为空白（null 或全空白字符）。
         *
         * @param value 待检查的字符串
         * @return true 表示字符串为 null 或空白，false 表示有实际内容
         */
        private boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }
}
