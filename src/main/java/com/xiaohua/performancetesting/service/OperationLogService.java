package com.xiaohua.performancetesting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiaohua.performancetesting.entity.OperationLog;
import com.xiaohua.performancetesting.mapper.OperationLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class OperationLogService extends ServiceImpl<OperationLogMapper, OperationLog> {

    private static final Logger LOGGER = LoggerFactory.getLogger(OperationLogService.class);

    /** operation_log.detail -> VARCHAR(500)，operation_log.username -> VARCHAR(50) */
    private static final int MAX_DETAIL_LEN = 500;
    private static final int MAX_USERNAME_LEN = 50;

    /** 全部操作类型（管理端 + 用户端），前端筛选下拉框的打底枚举 */
    public static final List<String> KNOWN_ACTIONS = List.of(
            "ADMIN_LOGIN", "ADMIN_LOGOUT", "USER_LOGIN", "USER_LOGOUT", "PLACE_ORDER",
            "CREATE_USER", "UPDATE_USER", "DELETE_USER",
            "CREATE_GOODS", "UPDATE_GOODS", "DELETE_GOODS",
            "PAY_ORDER", "DELETE_ORDER", "DB_RESET");

    /**
     * 用户端行为（普通用户登录、下单）是否写操作日志。
     * 压测时这些写入落在热点路径上（每笔下单多一次 INSERT），
     * 做基准对比时可在 application.properties 里设为 false。
     */
    @Value("${operation-log.user-actions-enabled:true}")
    private boolean userActionsEnabled;

    /**
     * 记录一条操作日志。写入失败不能影响主业务
     * （以前 detail 超长会抛异常，导致“新增商品”实际已成功却返回操作失败）。
     *
     * @param operatorId 操作人 ID（管理员或普通用户的 user.id；列原名 admin_id，已改 operator_id）
     */
    public void log(Long operatorId, String username, String action, String detail) {
        try {
            OperationLog entity = new OperationLog();
            entity.setOperatorId(operatorId != null ? operatorId : 0L);
            entity.setUsername(truncate(username == null ? "" : username, MAX_USERNAME_LEN));
            entity.setAction(truncate(action, 50));
            entity.setDetail(truncate(detail, MAX_DETAIL_LEN));
            entity.setRole(currentRole());
            entity.setIp(currentIp());
            entity.setCreateTime(LocalDateTime.now());
            save(entity);
        } catch (Exception e) {
            LOGGER.warn("failed to write operation_log, action={}, reason={}", action, e.getMessage());
        }
    }

    /**
     * 用户端行为日志（USER_LOGIN / PLACE_ORDER）。
     * 只记录"改变状态的成功操作"：查询类不记（300 并发下会把日志表刷爆），
     * 失败登录不记（避免被爆破尝试灌满）。
     */
    public void logUser(Long operatorId, String username, String action, String detail) {
        if (!userActionsEnabled) {
            return;
        }
        log(operatorId, username, action, detail);
    }

    public boolean isUserActionsEnabled() {
        return userActionsEnabled;
    }

    /** keyword 匹配操作人 / 操作类型 / 操作详情；action 可选精确筛选 */
    public List<OperationLog> recent(int limit, String keyword, String action) {
        LambdaQueryWrapper<OperationLog> w = new LambdaQueryWrapper<OperationLog>()
                .orderByDesc(OperationLog::getCreateTime)
                .orderByDesc(OperationLog::getId);
        if (StringUtils.hasText(action)) {
            w.eq(OperationLog::getAction, action.trim());
        }
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            w.and(wrapper -> wrapper
                    .like(OperationLog::getUsername, kw)
                    .or()
                    .like(OperationLog::getAction, kw)
                    .or()
                    .like(OperationLog::getDetail, kw));
        }
        w.last(" LIMIT " + Math.max(1, limit));
        return list(w);
    }

    public List<OperationLog> recent(int limit, String keyword) {
        return recent(limit, keyword, null);
    }

    /** 日志中实际出现过的操作类型（合并已知枚举，保证下拉框稳定） */
    public List<String> distinctActions() {
        List<String> found = listObjs(new LambdaQueryWrapper<OperationLog>()
                        .select(OperationLog::getAction)
                        .groupBy(OperationLog::getAction)
                        .orderByAsc(OperationLog::getAction),
                Object::toString);
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>(KNOWN_ACTIONS);
        set.addAll(found);
        return List.copyOf(set);
    }

    /**
     * 审计 IP 是否采信 X-Forwarded-For / X-Real-IP。默认 false：
     * 本项目是 Tomcat 直接对外（浏览器 / JMeter 直连），这些头客户端想怎么写就怎么写，
     * 无条件信任等于让被审计者自己填“来源”，只有前置 nginx/SLB 会覆写这些头时才打开。
     */
    @Value("${ip.trust-forwarded-headers:false}")
    private boolean trustForwardedHeaders;

    /**
     * 当前请求的来源 IP。日志服务自己从请求上下文取，避免 4 个控制器各写一遍、
     * 也避免把 IP 塞进 detail（那样没法单列展示与检索）。
     * 非请求线程（例如以后改异步写日志）取不到请求，记 '-'，不影响主业务。
     */
    private String currentIp() {
        jakarta.servlet.http.HttpServletRequest request = currentRequest();
        return request == null ? "-" : com.xiaohua.performancetesting.util.IpUtil.clientIp(request, trustForwardedHeaders);
    }

    /** 操作人角色：JwtInterceptor 已从 Token 声明里放进 request 属性，不额外查库 */
    private Integer currentRole() {
        Object role = currentRequestAttribute("role");
        if (role instanceof Integer i) {
            return i;
        }
        if (role instanceof Number n) {
            return n.intValue();
        }
        if (role instanceof String str && str.matches("[01]")) {
            return Integer.valueOf(str);
        }
        return null;
    }

    private jakarta.servlet.http.HttpServletRequest currentRequest() {
        Object attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes servlet) {
            return servlet.getRequest();
        }
        return null;
    }

    private Object currentRequestAttribute(String name) {
        jakarta.servlet.http.HttpServletRequest request = currentRequest();
        return request == null ? null : request.getAttribute(name);
    }

    /** 分页查询：管理端日志面板用（以前一次性返回 limit 条，没法翻页） */
    public com.baomidou.mybatisplus.core.metadata.IPage<OperationLog> pageQuery(long page, long size,
                                                                                String keyword, String action) {
        LambdaQueryWrapper<OperationLog> w = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(action)) {
            w.eq(OperationLog::getAction, action.trim());
        }
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            w.and(wrapper -> wrapper
                    .like(OperationLog::getUsername, kw)
                    .or()
                    .like(OperationLog::getAction, kw)
                    .or()
                    .like(OperationLog::getDetail, kw)
                    .or()
                    .like(OperationLog::getIp, kw));
        }
        // create_time 同秒并发写入很多，用 id 兼做稳定次级排序
        w.orderByDesc(OperationLog::getCreateTime).orderByDesc(OperationLog::getId);
        long p = page < 1 ? 1 : page;
        long sz = size < 1 ? 10 : Math.min(size, 200);
        return page(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(p, sz), w);
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
