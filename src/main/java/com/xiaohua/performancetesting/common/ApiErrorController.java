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
 * <p>405 / 415 / 404 这类错误在进入 Controller 方法之前就被容器处理掉了，
 * {@link GlobalExceptionHandler} 的 @ExceptionHandler 拦不到它们；不接管的话它们会以
 * Spring Boot 默认错误体（{"timestamp","status","error","path"}）或空响应体返回，
 * 里面没有 code/msg，前端只能显示“操作失败”。
 *
 * <p>本类只换响应体形状，HTTP 状态码保持不变。
 */
@RestController
public class ApiErrorController implements ErrorController {

    /**
     * /error 的唯一处理器。返回体是项目的 Result，HTTP 状态码沿用容器判定的那个。
     *
     * <p>msg 优先用容器给的错误消息，没有则退回该状态码的标准 reason phrase，
     * 保证前端 errMsg() 永远拿得到一段可翻译的文本。
     */
    @RequestMapping("/error")
    public ResponseEntity<Result<Void>> error(HttpServletRequest request) {
        int status = statusCode(request);
        String msg = message(request, status);
        HttpStatus httpStatus = HttpStatus.resolve(status) != null ? HttpStatus.valueOf(status) : null;
        String reason = httpStatus != null ? httpStatus.getReasonPhrase() : "request failed";
        return ResponseEntity.status(status).body(Result.fail(status, msg == null || msg.isEmpty() ? reason : msg));
    }

    /** 容器写的状态码；属性缺失或不是正数时按 500 处理（宁可报服务端错，也不假装成功）。 */
    private int statusCode(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (code instanceof Integer i && i > 0) return i;
        return 500;
    }

    /**
     * 取容器给的错误消息；没有消息时退到异常类型名（例如 {@code HttpRequestMethodNotSupportedException: ...}）。
     *
     * <p>这里刻意只取 simple name 不打堆栈：完整异常信息留在服务端日志里。
     */
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
