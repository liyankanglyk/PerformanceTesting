package com.xiaohua.performancetesting.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xiaohua.performancetesting.entity.User;

public interface UserService extends IService<User> {

    String login(String username, String password, String timestamp);

    Integer getRoleByUsername(String username);
}
