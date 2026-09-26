package com.xiaohua.performancetesting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 压测商城入口。端口、数据源、JWT 等配置全部在 application.properties。
 *
 * <p>启动时会按 {@code db.init-mode} 决定是否执行初始化脚本（performance_testing.sql）重建库内容，
 * 详见 {@link com.xiaohua.performancetesting.service.DatabaseInitService}。
 */
@SpringBootApplication
public class PerformanceTestingApplication {

    /** 启动类不含业务逻辑；数据初始化在容器启动后由 ApplicationRunner 执行。 */
    public static void main(String[] args) {
        SpringApplication.run(PerformanceTestingApplication.class, args);
    }

}
