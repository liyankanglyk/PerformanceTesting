package com.xiaohua.performancetesting.config;

import com.xiaohua.performancetesting.interceptor.AdminInterceptor;
import com.xiaohua.performancetesting.interceptor.JwtInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 配置：注册两个拦截器 + 保留旧文档地址跳转。
 *
 * <p>拦截器顺序按注册顺序生效：先 JwtInterceptor 验签（失败 401），
 * 再 AdminInterceptor 查角色（失败 403）。顺序反了会让无效 Token 变成 403，
 * 前端就分不清该跳登录页还是该提示无权限。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** 验签拦截器：负责 401，注册时必须排在前面 */
    private final JwtInterceptor jwtInterceptor;
    /** 角色拦截器：负责 403，只挂 /api/admin/** */
    private final AdminInterceptor adminInterceptor;

    /** 构造注入两个拦截器。谁先注册谁先跑：JwtInterceptor 必须在前，否则 401/403 的先后会颠倒。 */
    public WebConfig(JwtInterceptor jwtInterceptor, AdminInterceptor adminInterceptor) {
        this.jwtInterceptor = jwtInterceptor;
        this.adminInterceptor = adminInterceptor;
    }

    /**
     * /api/** 全部要求登录，只有登录接口与文档资源放行；
     * /api/admin/** 在此之上再加一道管理员校验。
     * 静态页面（/admin.html、/index.html）本身不鉴权，数据接口鉴权。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/user/login",
                        "/doc.html",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/v3/api-docs/**",
                        "/webjars/**"
                );

        registry.addInterceptor(adminInterceptor)
                .addPathPatterns("/api/admin/**");
    }

    /**
     * /doc.html 与 /swagger-ui.html 重定向到 Swagger UI。
     * 项目文档和很多人的浏览器书签用的都是这两个旧地址，去掉跳转会让它们 404。
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/doc.html", "/swagger-ui/index.html");
        registry.addRedirectViewController("/swagger-ui.html", "/swagger-ui/index.html");
    }
}
