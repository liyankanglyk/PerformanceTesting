package com.xiaohua.performancetesting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户表实体。普通用户与管理员同表，靠 {@link #role} 区分。
 *
 * <p>两端登录共用同一个接口 {@code POST /api/user/login}（管理端没有独立登录接口），
 * 登录成功后响应里带 role，前端自己决定跳 admin.html 还是 index.html；
 * 服务端不信任这个前端跳转，管理端请求仍由 AdminInterceptor 按 Token 里的 role 判 403。
 */
@Data
@TableName("user")
public class User {

    /** 自增主键，同时是 JWT 的 subject。重置库后自增从 1 重新开始，所以旧 Token 的 subject 可能指向另一个账号 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录名。库上有唯一索引（内联 UNIQUE 建的索引与列同名，就叫 username）；utf8_unicode_ci 排序规则大小写不敏感，ADMIN/admin 视为同一个 */
    private String username;

    /**
     * 登录口令。**注意：库里存的是明文**（种子数据就是 123456，新增用户也原样入库）。
     *
     * <p>明文只出现在数据库里；网络上跑的是
     * {@code MD5(该值 + timestamp)}，见 UserService#login。
     * 这是压测演示项目的简化实现，不是可以直接搬去生产的登录方案：
     * 库里应存加盐摘要（BCrypt），那样服务端就无法再用库里的值反推客户端摘要了。
     */
    private String password;

    /** 角色：0=普通用户 / 1=管理员。写进 Token 声明，管理端拦截器据此放行或 403 */
    private Integer role;

    /** 注册时间。后台用户列表按 id 升序展示，不按它排 */
    private LocalDateTime createTime;
}
