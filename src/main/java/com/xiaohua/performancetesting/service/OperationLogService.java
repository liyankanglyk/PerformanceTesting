package com.xiaohua.performancetesting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiaohua.performancetesting.entity.OperationLog;
import com.xiaohua.performancetesting.mapper.OperationLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class OperationLogService extends ServiceImpl<OperationLogMapper, OperationLog> {

    public void log(Long adminId, String username, String action, String detail) {
        OperationLog log = new OperationLog();
        log.setAdminId(adminId);
        log.setUsername(username);
        log.setAction(action);
        log.setDetail(detail);
        log.setCreateTime(LocalDateTime.now());
        save(log);
    }

    public List<OperationLog> recent(int limit, String keyword) {
        LambdaQueryWrapper<OperationLog> w = new LambdaQueryWrapper<OperationLog>()
                .orderByDesc(OperationLog::getCreateTime);
        if (StringUtils.hasText(keyword)) {
            w.and(wrapper -> wrapper
                    .like(OperationLog::getUsername, keyword)
                    .or()
                    .like(OperationLog::getAction, keyword));
        }
        w.last(" LIMIT " + limit);
        return list(w);
    }
}
