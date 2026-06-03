package com.mytext.learningassistant.rag;

public record RagUsageResponse(
    int dailyLimit,
    long usedToday,
    Long remainingToday,
    boolean unlimited
) {
}
