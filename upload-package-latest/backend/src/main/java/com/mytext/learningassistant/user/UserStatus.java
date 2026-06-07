package com.mytext.learningassistant.user;

/**
 * 用户状态枚举。
 * <p>
 * 定义用户账户的当前状态，用于控制用户是否能够正常使用系统功能。
 * 管理员可以通过后台管理界面修改用户的状态。
 */
public enum UserStatus {

    /** 活跃状态，用户可以正常登录并使用系统所有功能 */
    ACTIVE,

    /** 已禁用状态，用户无法登录系统，通常由管理员操作将违规或异常用户禁用 */
    DISABLED
}
