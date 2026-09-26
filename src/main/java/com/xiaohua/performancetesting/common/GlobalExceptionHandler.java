package com.xiaohua.performancetesting.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

/**
 * 统一异常出口。
 *
 * 修复前：任何未捕获异常（空请求体、字段超长、唯一索引冲突、错误的 HTTP 方法等）
 * 都会返回 Spring 默认错误体（形如 {"timestamp":..,"status":500,"error":..,"path":..}），
 * 里面没有 code/msg 字段，管理端页面只能显示“操作失败”，管理员看不到真实原因。
 *
 * 修复后：所有异常都转换成项目统一的 Result 结构；
 * 框架级错误（400/405/415/406 等）保持对应 HTTP 状态码，
 * 业务级错误（重名、字段值非法）沿用 Controller 自身的 “HTTP 200 + code” 风格。
 */
@RestControllerAdvice(basePackages = "com.xiaohua.performancetesting.controller")
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 请求体缺失 / 不是合法 JSON */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("invalid request body: {}", e.getMessage());
        return badRequest("invalid request body");
    }

    /** 缺少必需请求头（例如登录接口要求 ts） */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Result<Void>> handleMissingHeader(MissingRequestHeaderException e) {
        return badRequest("missing required header: " + e.getHeaderName());
    }

    /**
     * 405 / 406 / 415 / 缺参 等 Spring MVC 框架错误：保留 HTTP 状态码 + 统一响应体。
     * （@ExceptionHandler 只能声明 Throwable 子类，所以这里枚举具体类型，统一按 ErrorResponse 读取状态码）
     */
    @ExceptionHandler({HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeNotSupportedException.class,
            HttpMediaTypeNotAcceptableException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<Result<Void>> handleErrorResponse(Exception e) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        String detail = e.getMessage();
        if (e instanceof ErrorResponse errorResponse) {
            if (errorResponse.getStatusCode() instanceof HttpStatus http) status = http;
            if (errorResponse.getBody() != null && errorResponse.getBody().getDetail() != null) {
                detail = errorResponse.getBody().getDetail();
            }
        }
        return ResponseEntity.status(status).body(Result.fail(status.value(),
                detail == null ? status.getReasonPhrase() : detail));
    }

    /**
     * 参数类型不匹配（?page=abc、?size=1.5、?windowMinutes=abc）。
     * 不能并入上面的分组：它不实现 ErrorResponse，拿不到状态码，会被当成 500；
     * 也不能直接把 getMessage() 丢给前端，那是 Java 转换异常原文（内部类名全泄）。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        // 日志注入防护：参数值是客户端可控的，带 \r\n 能伪造日志行
        String value = String.valueOf(e.getValue()).replaceAll("[\\r\\n]", "_");
        log.warn("invalid parameter type: {} = {}", e.getName(), value);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(400, "invalid parameter: " + e.getName()));
    }

    /** 唯一索引冲突（并发创建同名用户/重复订单号） */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("duplicate key: {}", e.getMessage());
        return Result.fail("record already exists");
    }

    /** 字段值超出数据库约束（超长、类型不符等） */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public Result<Void> handleDataViolation(DataIntegrityViolationException e) {
        log.warn("data integrity violation: {}", e.getMessage());
        return Result.fail(400, "invalid field value for this operation");
    }

    /** 兜底：不再把堆栈式默认错误体暴露给前端 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception e) {
        log.error("unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(500, "internal server error: " + e.getClass().getSimpleName()));
    }

    private static ResponseEntity<Result<Void>> badRequest(String msg) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Result.fail(400, msg));
    }
}
