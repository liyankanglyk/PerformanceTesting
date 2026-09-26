package com.xiaohua.performancetesting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("operation_log")
public class OperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作人 ID：管理员或普通用户的 user.id（字段原名 admin_id，现已支持普通用户操作） */
    private Long operatorId;

    private String username;

    /** 操作人角色快照：0 普通用户 / 1 管理员（取自 Token 声明，改角色后新登录才会变） */
    private Integer role;

    private String action;

    private String detail;

    /** 来源 IP，统一 IPv4 写法；与 detail 分开成列，日志表格才能单列展示 */
    private String ip;

    private LocalDateTime createTime;
}
