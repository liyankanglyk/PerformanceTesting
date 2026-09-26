package com.xiaohua.performancetesting.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xiaohua.performancetesting.entity.User;

/**
 * 用户服务：登录校验与 Token 签发。
 */
public interface UserService extends IService<User> {

    /**
     * 校验登录信息并签发 JWT。
     *
     * <p>摘要算法两端必须完全一致：前端 {@code md5(明文口令 + ts)}，
     * 服务端 {@code md5(库中 password + ts)}；因为库里存的就是明文，两者才会相等。
     * 字符串拼接按 UTF-8 取字节（Md5Util），中文口令才不会算出不同摘要。
     *
     * @param username  登录名
     * @param password  前端提交的摘要，不是明文
     * @param timestamp 毫秒时间戳，与请求头 ts 同一个值；参与摘要计算，使同一口令每次摘要都不同
     * @return 签名后的 Token；用户名不存在或摘要不匹配一律返回 {@code null}，
     *         由调用方统一回 401 invalid username or password，不区分两种失败以免被枚举出有效账号
     * @see com.xiaohua.performancetesting.controller.UserController#login 时间戳新鲜度在这里校验（±5 分钟）
     */
    String login(String username, String password, String timestamp);

    /** 查角色（0/1），只 select role 列；用户不存在或角色为空按 0 处理，绝不抛异常。 */
    Integer getRoleByUsername(String username);
}
