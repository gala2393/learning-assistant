package com.mytext.learningassistant.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器 — 使用 @RestControllerAdvice 拦截所有 Controller 抛出的异常，
 * 统一转换为标准的 {@link ApiResponse} JSON 格式返回给前端。
 * <p>
 * 这样前端就不需要针对每种异常做不同的处理，始终收到 {@code {code, message, data}} 格式的响应。
 * <p>
 * 处理的异常类型：
 * <ul>
 *   <li>{@link BusinessException} — 自定义业务异常（如参数错误、未登录等）</li>
 *   <li>{@link MethodArgumentNotValidException} — 参数校验失败（@Valid 注解触发）</li>
 *   <li>{@link HttpRequestMethodNotSupportedException} — HTTP 方法不支持</li>
 *   <li>{@link NoResourceFoundException} — 请求的 URL 不存在</li>
 *   <li>{@link MaxUploadSizeExceededException} — 上传文件超过大小限制</li>
 *   <li>{@link Exception} — 所有其他未预期的异常（兜底处理）</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 日志记录器，用于记录未预期的异常堆栈信息，方便排查问题 */
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理自定义业务异常 — 根据 error code 映射为对应的 HTTP 状态码。
     * <p>
     * 映射规则：
     * <ul>
     *   <li>401 → HTTP 401 Unauthorized（未登录或 Token 失效）</li>
     *   <li>403 → HTTP 403 Forbidden（权限不足）</li>
     *   <li>404 → HTTP 404 Not Found（资源不存在）</li>
     *   <li>409 → HTTP 409 Conflict（数据冲突，如唯一约束违反）</li>
     *   <li>429 → HTTP 429 Too Many Requests（请求过于频繁）</li>
     *   <li>428 → HTTP 428 Precondition Required（前置条件不满足）</li>
     *   <li>413 → HTTP 413 Payload Too Large（文件太大）</li>
     *   <li>500 → HTTP 500 Internal Server Error（服务器内部错误）</li>
     *   <li>其他 → HTTP 400 Bad Request（通用客户端错误）</li>
     * </ul>
     *
     * @param exception 业务异常
     * @return 包含错误码和错误信息的 HTTP 响应
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        // 将业务错误码映射为 HTTP 状态码
        HttpStatus status = switch (exception.getCode()) {
            case 401 -> HttpStatus.UNAUTHORIZED;       // 未登录
            case 403 -> HttpStatus.FORBIDDEN;          // 权限不足
            case 404 -> HttpStatus.NOT_FOUND;          // 资源不存在
            case 409 -> HttpStatus.CONFLICT;           // 数据冲突（如唯一约束）
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;  // 请求过于频繁
            case 428 -> HttpStatus.PRECONDITION_REQUIRED; // 前置条件不满足
            case 413 -> HttpStatus.PAYLOAD_TOO_LARGE;  // 文件太大
            case 500 -> HttpStatus.INTERNAL_SERVER_ERROR; // 服务器内部错误
            default  -> HttpStatus.BAD_REQUEST;        // 其他错误码统一返回 400
        };
        return ResponseEntity.status(status).body(ApiResponse.error(exception.getCode(), exception.getMessage()));
    }

    /**
     * 处理参数校验失败异常 — 当 Controller 方法的 @Valid 校验失败时触发。
     * 把每个校验失败的字段名和错误信息收集到 Map 中返回，方便前端逐个字段显示错误提示。
     * <p>
     * 返回示例：
     * <pre>{"code":400, "message":"参数校验失败", "data":{"username":"用户名不能为空","password":"密码长度需在8-64位之间"}}</pre>
     *
     * @param exception 参数校验异常
     * @return 包含各字段错误信息的 HTTP 响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidation(
            MethodArgumentNotValidException exception) {
        Map<String, String> errors = new LinkedHashMap<>();  // 使用 LinkedHashMap 保持字段顺序
        List<FieldError> fieldErrors = exception.getBindingResult().getFieldErrors();
        for (FieldError fieldError : fieldErrors) {
            // putIfAbsent 确保同一个字段只保留第一条错误信息
            errors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return ResponseEntity.badRequest()
            .body(ApiResponse.error(400, "参数校验失败", errors));
    }

    /**
     * 处理不支持的 HTTP 方法 — 比如用 GET 访问只接受 POST 的接口。
     *
     * @param exception HTTP 方法不支持异常
     * @return 提示用户检查后端版本或刷新页面的错误响应
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .body(ApiResponse.error(405, "当前服务尚未支持该操作，请重启后端或刷新到最新版本"));
    }

    /**
     * 处理资源未找到 — 请求了一个不存在的 URL 路径。
     *
     * @param exception 资源未找到异常
     * @return 404 错误响应
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(NoResourceFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiResponse.error(404, "请求的资源不存在"));
    }

    /**
     * 处理所有未预期的异常 — 这是最后的"兜底"处理器，捕获前面所有 handler 没处理的异常。
     * 记录完整的异常堆栈到日志中，方便排查问题，同时向前端返回友好的错误提示。
     *
     * @param exception 未预期的异常
     * @return 500 错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        log.error("Unexpected request failure", exception); // 记录完整堆栈到日志
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error(500, "服务器内部错误"));
    }

    /**
     * 处理文件上传超限 — 当上传的文件超过配置的 max-file-size 时触发。
     * 把字节数格式化为可读的 MB 值展示给用户。
     *
     * @param exception 文件大小超限异常
     * @return 413 错误响应，包含可读的文件大小限制信息
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSize(MaxUploadSizeExceededException exception) {
        long maxUploadSize = exception.getMaxUploadSize();  // 配置的最大文件大小（字节）
        String message = maxUploadSize > 0
            ? "文件太大，请控制在" + formatMegabytes(maxUploadSize) + "以内后再导入。"
            : "文件太大，请控制在500MB以内后再导入。";
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ApiResponse.error(413, message));
    }

    /**
     * 把字节数格式化为可读的 MB 值。
     * <p>
     * 示例：67108864 字节 → "64MB"，536870912 字节 → "512.0MB"
     *
     * @param bytes 字节数
     * @return 格式化后的 MB 字符串
     */
    private String formatMegabytes(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        // 如果是接近整数的值（如 63.98MB），直接显示为整数
        if (Math.abs(mb - Math.round(mb)) < 0.05) {
            return Math.round(mb) + "MB";
        }
        return String.format("%.1fMB", mb);
    }
}
