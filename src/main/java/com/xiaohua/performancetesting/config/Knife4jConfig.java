package com.xiaohua.performancetesting.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Knife4jConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("速购商城性能测试系统")
                        .description("""
                            ## 系统简介
                            SpringBoot 单体项目，专供 JMeter 性能压测练习，支持 300 用户并发。

                            ## 鉴权说明
                            - `/api/user/login` 为公开接口，无需 Token
                            - 其余 `/api/**` 接口需要在请求头中携带 `token`（JWT Token，有效期 1 小时）
                            - `/api/admin/**` 管理接口还需要管理员角色（role=1），否则返回 403

                            ## 密码加密规则
                            客户端将明文密码与时间戳拼接后做一次 MD5：
                            `password = MD5(明文密码 + 时间戳)`，时间戳通过请求头 `ts` 传入（毫秒级）

                            ## 测试账号
                            | 用户名 | 密码 | 角色 |
                            |--------|------|------|
                            | admin | 123456 | 管理员 |
                            | test001 ~ test003 | 123456 | 普通用户 |

                            ## 统一响应格式
                            ```json
                            {
                              "code": 200,
                              "msg": "操作描述",
                              "data": {},
                              "timestamp": 1716307200000
                            }
                            ```
                            """)
                        .version("1.0"));
    }
}
