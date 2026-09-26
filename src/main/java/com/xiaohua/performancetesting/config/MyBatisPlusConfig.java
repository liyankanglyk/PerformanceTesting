package com.xiaohua.performancetesting.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.xiaohua.performancetesting.mapper")
public class MyBatisPlusConfig {

    /** 单页最大条数：管理端接口被误传 size=999999 时也不会一次拉全表 */
    public static final int MAX_PAGE_SIZE = 200;

    /**
     * 分页插件。
     * 没有这个 Bean 时 {@code IService.page(...)} 不会拼 LIMIT/做 count，
     * 等于把全表读进内存（压测后订单表几十万行时管理页会直接卡死）。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit((long) MAX_PAGE_SIZE);   // 超出按 maxLimit 处理，而不是无限放大
        pagination.setOverflow(false);              // 页码越界返回空列表，不回绕到第一页
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
