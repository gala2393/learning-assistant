package com.mytext.learningassistant.auth;

import java.util.List;

public record AuthUserResponse(
    Long id,
    String username,
    String nickname,
    String avatar,
    String role,
    String status,
    String createdAt,
    boolean canAccessAdminConsole,
    List<String> visibleRoutes,
    List<String> permissions
) {
}
