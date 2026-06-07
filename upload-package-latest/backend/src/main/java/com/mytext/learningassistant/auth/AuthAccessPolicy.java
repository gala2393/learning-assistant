package com.mytext.learningassistant.auth;

import java.util.List;

import com.mytext.learningassistant.user.UserRole;

/**
 * 权限策略 — 集中管理不同角色的路由访问权限和权限标识。
 * <p>
 * 这是一个工具类（final + private constructor），只包含静态方法，
 * 用于在 AuthService 生成用户响应时附加权限信息，前端以此决定显示哪些菜单。
 * <p>
 * 权限层级：
 * <ul>
 *   <li>USER（普通用户）：只能访问工作区（workspace），权限标识为 WORKSPACE</li>
 *   <li>ADMIN（管理员）：可以访问工作区 + 管理后台，权限标识为 WORKSPACE + ADMIN_CONSOLE</li>
 * </ul>
 */
public final class AuthAccessPolicy {

    /** 普通用户可见的路由列表 */
    private static final List<String> USER_ROUTES = List.of(
        "/workspace/chat",
        "/workspace/materials",
        "/workspace/reader",
        "/workspace/history",
        "/workspace/favorites",
        "/workspace/summary"
    );

    /** 管理员可见的路由列表（包含普通用户的所有路由 + 管理后台路由） */
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

    /** 普通用户的权限标识列表 */
    private static final List<String> USER_PERMISSIONS = List.of("WORKSPACE");
    /** 管理员的权限标识列表 */
    private static final List<String> ADMIN_PERMISSIONS = List.of("WORKSPACE", "ADMIN_CONSOLE");

    /** 私有构造器防止实例化（工具类模式） */
    private AuthAccessPolicy() {
    }

    /** 判断指定角色是否可以访问管理后台 */
    public static boolean canAccessAdminConsole(UserRole role) {
        return role == UserRole.ADMIN;
    }

    /** 获取指定角色可见的前端路由列表 */
    public static List<String> visibleRoutes(UserRole role) {
        return canAccessAdminConsole(role) ? ADMIN_ROUTES : USER_ROUTES;
    }

    /** 获取指定角色的权限标识列表（用于前端权限检查） */
    public static List<String> permissions(UserRole role) {
        return canAccessAdminConsole(role) ? ADMIN_PERMISSIONS : USER_PERMISSIONS;
    }
}
