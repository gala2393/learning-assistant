package com.mytext.learningassistant.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一 API 响应格式 — 所有后端接口都返回这个结构，前端无需针对每个接口做不同的解析。
 * <p>
 * 响应 JSON 示例：
 * <pre>{@code
 * // 成功 {"code":0, "message":"ok", "data":{...}}
 * // 失败 {"code":400, "message":"用户名已存在", "data":null}
 * }</pre>
 * <p>
 * {@code @JsonInclude(NON_NULL)} 表示如果 data 是 null，JSON 中不输出 "data":null 这一行，
 * 让返回给前端的 JSON 更干净。
 * <p>
 * 这是一个 Java Record（Java 16+ 特性），自动生成构造器、getter、equals/hashCode、toString。
 *
 * @param <T> 响应数据的类型（可以是任意 Java 对象）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(int code, String message, T data) {

    /**
     * 创建成功响应（code=0, message="ok"）。
     * 示例：ApiResponse.ok(userObj) → {"code":0,"message":"ok","data":{...user字段...}}
     *
     * @param data 返回给前端的数据对象
     * @param <T>  数据类型
     * @return 成功响应
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    /**
     * 创建失败响应，不附带额外数据（data=null，JSON中不出现）。
     * 示例：ApiResponse.error(400, "用户名已存在")
     *
     * @param code    错误码（如 400=参数错误，401=未登录，403=无权限，404=不存在）
     * @param message 错误描述信息
     * @param <T>     数据类型
     * @return 错误响应
     */
    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    /**
     * 创建失败响应，附带额外数据（如参数校验失败时返回各字段的错误信息）。
     * 示例：ApiResponse.error(400, "参数校验失败", Map.of("username","不能为空"))
     *
     * @param code    错误码
     * @param message 错误描述
     * @param data    附加的错误详情
     * @param <T>     数据类型
     * @return 错误响应
     */
    public static <T> ApiResponse<T> error(int code, String message, T data) {
        return new ApiResponse<>(code, message, data);
    }
}
