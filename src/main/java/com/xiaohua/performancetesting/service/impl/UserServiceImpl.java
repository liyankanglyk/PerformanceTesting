package com.xiaohua.performancetesting.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiaohua.performancetesting.entity.User;
import com.xiaohua.performancetesting.mapper.UserMapper;
import com.xiaohua.performancetesting.service.UserService;
import com.xiaohua.performancetesting.util.JwtUtil;
import com.xiaohua.performancetesting.util.Md5Util;
import org.springframework.stereotype.Service;

/**
 * 登录实现：只负责“口令对不对 + 发 Token”，不管时间戳新鲜度（那是控制器的防重放校验）。
 *
 * <p>比对方式：前端算 {@code md5(明文口令 + ts)}，服务端算 {@code md5(库中 password + ts)}。
 * 因为库里 password 存的是明文（见 User#password），两值才会相等；
 * 哪天改成存摘要，这里必须同步改，否则所有人登录不上。
 *
 * <p>前端算法见 {@code static/js/login.html} 与 {@code js/api.js} 的 md5/utf8Encode，
 * 两边必须逐字节一致——中文口令要先按 UTF-8 编码再取摘要，
 * 这也是 Md5Util 固定使用 UTF-8 的原因。
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    /** 口令校验通过后用它签发 Token */
    private final JwtUtil jwtUtil;

    /** 构造注入 JwtUtil，口令比对通过后签发 Token。 */
    public UserServiceImpl(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    /** 比对摘要并签发 Token；失败只返回 null，不抛异常也不区分“用户不存在 / 口令错”，见接口注释。 */
    @Override
    public String login(String username, String password, String timestamp) {
        // 期望值 = md5(库中 password + ts)。库里存明文，所以它等价于前端算的 md5(明文口令 + ts)。
        // 这里用的是普通 String.equals（会短路），严格说存在时序侧信道；本项目是压测演示，
        // 口令本身就是 123456 这种，未做加固。真要换成存摘要方案时应改用 MessageDigest.isEqual。
        User user = getOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username));
        if (user == null) {
            return null;
        }
        String expected = Md5Util.md5(user.getPassword() + timestamp);
        if (!expected.equals(password)) {
            return null;
        }
        // role 允许为 NULL（没有该列值的历史行），一律按普通用户处理，别让它变成管理端 403 的谜团
        Integer role = user.getRole() != null ? user.getRole() : 0;
        return jwtUtil.generateToken(user.getId(), user.getUsername(), role);
    }

    /**
     * 只取 role 一列。供 AdminInterceptor 每次管理端请求校验身份用，
     * 因此这条查询在每个 /api/admin/** 请求上都会跑一次，保持最轻。
     */
    @Override
    public Integer getRoleByUsername(String username) {
        User user = getOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username)
                .select(User::getRole));
        return user != null && user.getRole() != null ? user.getRole() : 0;
    }
}
