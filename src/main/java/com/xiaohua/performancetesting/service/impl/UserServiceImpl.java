package com.xiaohua.performancetesting.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiaohua.performancetesting.entity.User;
import com.xiaohua.performancetesting.mapper.UserMapper;
import com.xiaohua.performancetesting.service.UserService;
import com.xiaohua.performancetesting.util.JwtUtil;
import com.xiaohua.performancetesting.util.Md5Util;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private final JwtUtil jwtUtil;

    public UserServiceImpl(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public String login(String username, String password, String timestamp) {
        // Client sends MD5(plaintext + timestamp) as password
        // Server computes MD5(stored_password + timestamp) and compares
        User user = getOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username));
        if (user == null) {
            return null;
        }
        String expected = Md5Util.md5(user.getPassword() + timestamp);
        if (!expected.equals(password)) {
            return null;
        }
        Integer role = user.getRole() != null ? user.getRole() : 0;
        return jwtUtil.generateToken(user.getId(), user.getUsername(), role);
    }

    @Override
    public Integer getRoleByUsername(String username) {
        User user = getOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username)
                .select(User::getRole));
        return user != null && user.getRole() != null ? user.getRole() : 0;
    }
}
