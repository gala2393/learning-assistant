package com.mytext.learningassistant.auth;

import java.util.List;

/**
 * 用户信息响应记录类
 * 用于封装登录成功后返回给前端的用户详细信息
 * 包含用户基本信息、权限控制和可见路由等数据
 */
public record AuthUserResponse(
    /** 用户唯一标识ID */
    Long id,

    /** 用户登录名 */
    String username,

    /** 用户昵称，用于界面显示 */
    String nickname,

    /** 用户头像URL地址 */
    String avatar,

    /** 用户角色（如：admin、user等） */
    String role,

    /** 用户账号状态（如：active、disabled等） */
    String status,

    /** 账号创建时间，格式为字符串 */
    String createdAt,

    /** 是否有权访问管理控制台，true表示可以访问 */
    boolean canAccessAdminConsole,

    /** 用户可见的前端路由列表，用于控制菜单显示 */
    List<String> visibleRoutes,

    /** 用户权限列表，包含具体的操作权限标识 */
    List<String> permissions
) {
}
