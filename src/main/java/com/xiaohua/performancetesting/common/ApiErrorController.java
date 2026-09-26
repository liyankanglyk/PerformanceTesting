package com.xiaohua.performancetesting.common;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /error 出口。
 *
 * 405 / 415 / 404 这类异常发生在“还没有进到 Controller 方法之前”，
 * {@link GlobalExceptionHandler} 的 @ExceptionHandler 拦不到，
 * 之前会返回 Spring Boot 默认错误体（{"timestamp":..,"status":..,"error":..,"path":..}）
 * 或空响应体，前端拿不到 code/msg。
 * 这里统一翻译成项目的 Result 结构，HTTP 状态码保持不变。
 */
@RestController
public class ApiErrorController implements ErrorController {

    @RequestMapping("/error")
    public ResponseEntity<Result<Void>> error(HttpServletRequest request) {
        int status = statusCode(request);
        String msg = message(request, status);
        HttpStatus httpStatus = HttpStatus.resolve(status) != null ? HttpStatus.valueOf(status) : null;
        String reason = httpStatus != null ? httpStatus.getReasonPhrase() : "request failed";
        return ResponseEntity.status(status).body(Result.fail(status, msg == null || msg.isEmpty() ? reason : msg));
    }

    private int statusCode(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (code instanceof Integer i && i > 0) return i;
        return 500;
    }

    private String message(HttpServletRequest request, int status) {
        Object msg = request.getAttribute(RequestDispatcher.ERROR_MESSAGE);
        if (msg instanceof String s && !s.isBlank()) return s;
        Object ex = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        if (ex instanceof Throwable t) {
            return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
        }
        return null;
    }
}
