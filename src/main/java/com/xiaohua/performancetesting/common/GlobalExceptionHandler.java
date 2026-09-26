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
 * 统一异常出口：把未捕获异常转换成项目的 Result 结构，保证响应体里一定有 code/msg。
 *
 * <p>状态码分两类处理，前端据此决定是弹窗提示还是跳登录页：
 * <ul>
 *   <li>框架级错误（空请求体 / 非法 JSON、缺请求头、405、406、415、参数类型不符）
 *       —— HTTP 状态码保持原值，响应体换成 Result；</li>
 *   <li>业务级错误（重名、字段值非法）—— 由 Controller 自己返回 “HTTP 200 + code”，
 *       根本不进这里，所以这里不能把所有异常一律改写成 400。</li>
 * </ul>
 *
 * <p>响应体只放短语化的 msg（前端 api.js 的 MSG_ZH 表按它翻译中文），
 * 异常原文、SQL、堆栈一律只进服务端日志，绝不出现在响应里。
 */
@RestControllerAdvice(basePackages = "com.xiaohua.performancetesting.controller")
public class GlobalExceptionHandler {

    /** 异常原文只进这里（含参数值、SQL 约束名等），响应体里只放短语化的 msg */
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
    /**
     * 框架级错误统一处理：状态码从异常自带的 ErrorResponse 里读（405/415/406/缺参各不相同），
     * 读不到才退回 500。
     *
     * <p>必须是这个形状而不是硬编码 400：JMeter 线程组里常有「非 2xx 即失败」的断言，
     * 状态码错了会让整轮压测结果失真。
     */
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

    /** 400 响应体的统一构造点：HTTP 状态码与 body.code 必须同为 400。 */
    private static ResponseEntity<Result<Void>> badRequest(String msg) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Result.fail(400, msg));
    }
}
