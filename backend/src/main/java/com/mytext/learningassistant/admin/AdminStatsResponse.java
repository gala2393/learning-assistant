package com.mytext.learningassistant.admin;

public record AdminStatsResponse(
    long userCount,
    long materialCount,
    long questionCount,
    long favoriteCount,
    long logCount
) {
}
