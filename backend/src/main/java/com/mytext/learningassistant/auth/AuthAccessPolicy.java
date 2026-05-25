package com.mytext.learningassistant.auth;

import java.util.List;

import com.mytext.learningassistant.user.UserRole;

public final class AuthAccessPolicy {

    private static final List<String> USER_ROUTES = List.of(
        "/workspace/chat",
        "/workspace/materials",
        "/workspace/reader",
        "/workspace/history",
        "/workspace/favorites",
        "/workspace/summary"
    );

    private static final List<String> ADMIN_ROUTES = List.of(
        "/workspace/chat",
        "/workspace/materials",
        "/workspace/reader",
        "/workspace/history",
        "/workspace/favorites",
        "/workspace/summary",
        "/admin/dashboard",
        "/admin/users",
        "/admin/materials",
        "/admin/logs"
    );

    private static final List<String> USER_PERMISSIONS = List.of("WORKSPACE");
    private static final List<String> ADMIN_PERMISSIONS = List.of("WORKSPACE", "ADMIN_CONSOLE");

    private AuthAccessPolicy() {
    }

    public static boolean canAccessAdminConsole(UserRole role) {
        return role == UserRole.ADMIN;
    }

    public static List<String> visibleRoutes(UserRole role) {
        return canAccessAdminConsole(role) ? ADMIN_ROUTES : USER_ROUTES;
    }

    public static List<String> permissions(UserRole role) {
        return canAccessAdminConsole(role) ? ADMIN_PERMISSIONS : USER_PERMISSIONS;
    }
}
