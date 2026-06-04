package com.mytext.learningassistant.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 邮箱验证码配置属性类
 * 通过 @ConfigurationProperties 注解绑定 application.yml 中的配置项
 * 配置前缀为 app.email-code，用于控制邮箱验证码服务的各项参数
 * 包含验证码有效期、发送频率限制、SMTP邮件服务器配置等
 */
@ConfigurationProperties(prefix = "app.email-code")
public class EmailCodeProperties {

    /** 是否启用邮箱验证码功能，默认关闭 */
    private boolean enabled = false;

    /** 验证码有效期，单位秒，默认300秒（5分钟） */
    private int ttlSeconds = 300;

    /** 同一邮箱发送验证码的冷却时间，单位秒，默认60秒 */
    private int emailCooldownSeconds = 60;

    /** 同一IP地址每小时最大请求数量限制，默认10次 */
    private int ipHourlyLimit = 10;

    /** SMTP连接超时时间，单位毫秒，默认10000毫秒（10秒） */
    private int connectTimeoutMillis = 10000;

    /** SMTP读取超时时间，单位毫秒，默认10000毫秒（10秒） */
    private int timeoutMillis = 10000;

    /** SMTP写入超时时间，单位毫秒，默认10000毫秒（10秒） */
    private int writeTimeoutMillis = 10000;

    /** 验证码邮件主题 */
    private String subject = "学习助手登录验证码";

    /** 邮件发送者地址，可在各SMTP账户中单独配置覆盖 */
    private String from = "";

    /** 默认使用的邮件服务商，可选值：qq、netease */
    private String defaultProvider = "qq";

    /** 当邮件功能未启用时，是否在日志中输出验证码（便于开发调试） */
    private boolean logCodeWhenMailDisabled = true;

    /** QQ邮箱SMTP账户配置 */
    private SmtpAccount qq = new SmtpAccount();

    /** 网易邮箱SMTP账户配置 */
    private SmtpAccount netease = new SmtpAccount();

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
     * SMTP账户配置内部类
     * 封装单个邮件服务商的SMTP连接参数
     * 支持QQ邮箱、网易邮箱等常见邮件服务商
     */
    public static class SmtpAccount {

        /** SMTP服务器地址，如：smtp.qq.com */
        private String host = "";

        /** SMTP服务器端口号，QQ邮箱SSL端口为465 */
        private int port = 465;

        /** SMTP登录用户名，通常为邮箱地址 */
        private String username = "";

        /** SMTP登录密码或授权码 */
        private String password = "";

        /** 该账户的发件人地址，可覆盖全局from配置 */
        private String from = "";

        /** 是否需要身份验证，通常为true */
        private boolean auth = true;

        /** 是否启用SSL加密连接，默认开启 */
        private boolean sslEnable = true;

        /** 是否启用STARTTLS加密，默认关闭（SSL已开启时通常不需要） */
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
         * 检查该SMTP账户是否已完整配置
         * 必须同时配置主机地址、用户名和密码才视为有效配置
         *
         * @return true表示配置完整可用，false表示配置不完整
         */
        public boolean isConfigured() {
            return !isBlank(host) && !isBlank(username) && !isBlank(password);
        }

        /**
         * 判断字符串是否为空白
         *
         * @param value 待检查的字符串
         * @return true表示字符串为null或空白，false表示有实际内容
         */
        private boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }
}
