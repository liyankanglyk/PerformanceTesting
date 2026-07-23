package com.xiaohua.performancetesting.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.xiaohua.performancetesting.mapper")
public class MyBatisPlusConfig {
}
