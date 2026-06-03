package com.mytext.learningassistant.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.query-expansion")
public class QueryExpansionProperties {

    private boolean enabled = true;
    private int maxQueries = 4;
    private boolean localFallback = true;
    private boolean hydeEnabled = true;
    private double hydeWeight = 0.72;

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int maxQueries() {
        return maxQueries <= 0 ? 4 : maxQueries;
    }

    public void setMaxQueries(int maxQueries) {
        this.maxQueries = maxQueries;
    }

    public boolean localFallback() {
        return localFallback;
    }

    public void setLocalFallback(boolean localFallback) {
        this.localFallback = localFallback;
    }

    public boolean hydeEnabled() {
        return hydeEnabled;
    }

    public void setHydeEnabled(boolean hydeEnabled) {
        this.hydeEnabled = hydeEnabled;
    }

    public double hydeWeight() {
        if (hydeWeight <= 0.0) {
            return 0.72;
        }
        return Math.min(1.0, hydeWeight);
    }

    public void setHydeWeight(double hydeWeight) {
        this.hydeWeight = hydeWeight;
    }
}
