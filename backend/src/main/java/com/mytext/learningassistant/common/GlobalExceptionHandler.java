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
 * 统一转换为标准的 ApiResponse JSON 格式返回给前端。
 * <p>
 * 这样前端就不需要针对每种异常做不同的处理，始终收到 {code, message, data} 格式的响应。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 日志记录器，用于记录未预期的异常 */
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理自定义业务异常 — 根据 error code 映射为对应的 HTTP 状态码。
     * 例如 code=401 → HTTP 401 Unauthorized，code=404 → HTTP 404 Not Found。
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
            case 413 -> HttpStatus.PAYLOAD_TOO_LARGE;  // 文件太大
            case 500 -> HttpStatus.INTERNAL_SERVER_ERROR;
            default  -> HttpStatus.BAD_REQUEST;        // 其他错误码统一返回 400
        };
        return ResponseEntity.status(status).body(ApiResponse.error(exception.getCode(), exception.getMessage()));
    }

    /**
     * 处理参数校验失败异常 — 当 Controller 方法的 @Valid 校验失败时触发。
     * 把每个校验失败的字段名和错误信息收集到 Map 中返回，方便前端逐个字段显示错误提示。
     * <p>
     * 例如返回：{"code":400,"message":"参数校验失败","data":{"username":"用户名不能为空","password":"密码长度需在8-64位之间"}}
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidation(
            MethodArgumentNotValidException exception) {
        Map<String, String> errors = new LinkedHashMap<>();  // 保持字段顺序
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
     * 提示用户检查后端版本或刷新页面。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .body(ApiResponse.error(405, "当前服务尚未支持该操作，请重启后端或刷新到最新版本"));
    }

    /**
     * 处理资源未找到 — 请求了一个不存在的 URL 路径。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(NoResourceFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiResponse.error(404, "请求的资源不存在"));
    }

    /**
     * 处理所有未预期的异常 — 这是最后的"兜底"，捕获前面所有 handler 没处理的异常。
     * 记录完整的异常堆栈到日志中，方便排查问题，同时向前端返回友好的错误提示。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        log.error("Unexpected request failure", exception); // 记录到日志
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error(500, "服务器内部错误"));
    }

    /**
     * 处理文件上传超限 — 当上传的文件超过配置的 max-file-size（500MB）时触发。
     * 把字节数格式化为可读的 MB 值展示给用户。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSize(MaxUploadSizeExceededException exception) {
        long maxUploadSize = exception.getMaxUploadSize();  // 配置的最大文件大小（字节）
        String message = maxUploadSize > 0
            ? "文件太大，请控制在" + formatMegabytes(maxUploadSize) + "以内后再导入。"
            : "文件太大，请控制在500MB以内后再导入。";
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ApiResponse.error(413, message));
    }

    /** 把字节数格式化为可读的 MB 值，如 67108864 → "64MB"，536870912 → "512.0MB" */
    private String formatMegabytes(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        // 如果是接近整数的值（如 63.98MB），直接显示为整数
        if (Math.abs(mb - Math.round(mb)) < 0.05) {
            return Math.round(mb) + "MB";
        }
        return String.format("%.1fMB", mb);
    }
}
