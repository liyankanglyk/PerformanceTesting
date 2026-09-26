package com.xiaohua.performancetesting.common;

import lombok.Data;

/**
 * 统一响应信封：{@code {code, msg, data, timestamp}}。
 *
 * <p>HTTP 状态码与 body 里的 code 保持一致（拦截器与 GlobalExceptionHandler 都这么做），
 * 这样前端只需读一个来源：fetch 非 2xx 时照样能解析出 code/msg。
 * 少数遗留接口只返回裸 {@code {msg:...}}，前端有兜底，不要拿这个类去猜全部接口。
 */
@Data
public class Result<T> {

    /** 业务/HTTP 状态码：200 成功，400 参数或业务校验失败，401 未登录，403 无权限，500 服务端异常 */
    private int code;

    /** 提示信息。前端 api.js 的 MSG_ZH 表按它翻译成中文，所以这里的取值必须是稳定的英文短语 */
    private String msg;

    /** 载荷；失败时为 null */
    private T data;

    /** 服务端生成响应的毫秒时间戳，便于压测时对齐日志与 JMeter 结果 */
    private long timestamp;

    /** 只允许下面四个工厂方法构造，保证 code/msg/timestamp 不会出现半截状态。 */
    private Result() {
    }

    /** 成功，msg 固定为 success（前端按它判断“写操作成功”的默认文案）。 */
    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code = 200;
        r.msg = "success";
        r.data = data;
        r.timestamp = System.currentTimeMillis();
        return r;
    }

    /** 成功并自定义 msg；写操作基本走这个，方便前端把提示原样弹出来。 */
    public static <T> Result<T> ok(String msg, T data) {
        Result<T> r = new Result<>();
        r.code = 200;
        r.msg = msg;
        r.data = data;
        r.timestamp = System.currentTimeMillis();
        return r;
    }

    /** 失败。code 要与 HTTP 状态码一致，否则前端弹的提示会和浏览器网络面板对不上。 */
    public static <T> Result<T> fail(int code, String msg) {
        Result<T> r = new Result<>();
        r.code = code;
        r.msg = msg;
        r.data = null;
        r.timestamp = System.currentTimeMillis();
        return r;
    }

    /** 失败且按 500 处理（只给兜底 catch 用；能定性的错误请显式传 code）。 */
    public static <T> Result<T> fail(String msg) {
        return fail(500, msg);
    }
}
