package com.mytext.learningassistant.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.email-code")
public class EmailCodeProperties {

    private boolean enabled = false;
    private int ttlSeconds = 300;
    private int emailCooldownSeconds = 60;
    private int ipHourlyLimit = 10;
    private int connectTimeoutMillis = 10000;
    private int timeoutMillis = 10000;
    private int writeTimeoutMillis = 10000;
    private String subject = "学习助手登录验证码";
    private String from = "";
    private String defaultProvider = "qq";
    private boolean logCodeWhenMailDisabled = true;
    private SmtpAccount qq = new SmtpAccount();
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

    public static class SmtpAccount {

        private String host = "";
        private int port = 465;
        private String username = "";
        private String password = "";
        private String from = "";
        private boolean auth = true;
        private boolean sslEnable = true;
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

        public boolean isConfigured() {
            return !isBlank(host) && !isBlank(username) && !isBlank(password);
        }

        private boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }
}
