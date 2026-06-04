package com.mytext.learningassistant.common;

/**
 * 自定义业务异常 — 当业务逻辑出现预期内的错误时（如"用户名已存在"、"密码错误"、
 * "未登录"、"验证码过期"等），抛这个异常。
 * <p>
 * 与普通 RuntimeException 的区别：它携带一个 {@code code} 错误码，
 * 被 {@link GlobalExceptionHandler} 捕获后会自动转为标准的 ApiResponse 格式返回给前端。
 * <p>
 * 使用示例：
 * <pre>{@code
 * throw new BusinessException(400, "用户名已存在");
 * throw new BusinessException(401, "未登录或登录已过期");
 * throw new BusinessException(404, "资料不存在");
 * }</pre>
 */
public class BusinessException extends RuntimeException {

    /** 错误码，常见值：400=参数错误，401=未登录，403=无权限，404=不存在，409=冲突，429=过于频繁，500=服务器错误 */
    private final int code;

    /**
     * 创建一个业务异常。
     *
     * @param code    错误码（会被 GlobalExceptionHandler 映射为对应的 HTTP 状态码）
     * @param message 可直接展示给用户的错误信息
     */
    public BusinessException(int code, String message) {
        super(message);          // 调用 RuntimeException 的构造器，设置异常消息
        this.code = code;
    }

    /** 获取错误码 */
    public int getCode() {
        return code;
    }
}
