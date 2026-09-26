package com.xiaohua.performancetesting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作日志一行。由 OperationLogService 在写操作成功后插入。
 *
 * <p>这一张表会随压测线性膨胀（300 并发下订单和日志同量级），
 * 除 ID 外没有任何唯一约束，也不参与业务读，压测前后可随「系统重置」一起清掉。
 */
@Data
@TableName("operation_log")
public class OperationLog {

    /** 自增主键，日志表格第一列 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作人 ID：管理员与普通用户都记在这一列，靠 {@link #role} 区分是谁干的 */
    private Long operatorId;

    /** 操作人登录名快照。账号改名或删号之后，日志里显示的仍是当时那个名字 */
    private String username;

    /** 操作人角色快照：0 普通用户 / 1 管理员（取自 Token 声明，改角色后新登录才会变） */
    private Integer role;

    /** 操作类型英文枚举（CREATE_GOODS、PAY_ORDER…）。界面按 api.js 的 ACTION_ZH 显示中文，原始值留在 title 里 */
    private String action;

    /** 中文摘要，最长 500 字符，超出由 OperationLogService#truncate 截断 */
    private String detail;

    /** 来源 IP，统一 IPv4 写法（IPv6 回环归一成 127.0.0.1）。单独成列而不是塞进 detail，日志表格才有独立的 IP 列 */
    private String ip;

    /** 写入时间。列表按它倒序；同一秒并发写入很多，再用 id 做次级倒序，翻页才不会跳记录 */
    private LocalDateTime createTime;
}
