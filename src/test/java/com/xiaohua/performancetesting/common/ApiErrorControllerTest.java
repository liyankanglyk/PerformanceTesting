package com.xiaohua.performancetesting.common;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 405 / 415 / 404 这类“还没进入 Controller 就失败”的请求，
 * 由 /error 统一翻译成项目响应格式（HTTP 状态码保持不变）。
 */
class ApiErrorControllerTest {

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ApiErrorController()).build();
    }

    @Test
    @DisplayName("415 Content-Type 错误：返回 code=415 且带可读 msg")
    void unsupportedMediaType() throws Exception {
        mvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 415)
                        .requestAttr(RequestDispatcher.ERROR_MESSAGE, "Content-Type 'text/plain' is not supported"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(415))
                .andExpect(jsonPath("$.msg").value("Content-Type 'text/plain' is not supported"))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("405 请求方法不支持：返回 code=405")
    void methodNotAllowed() throws Exception {
        mvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 405)
                        .requestAttr(RequestDispatcher.ERROR_MESSAGE, "Request method 'PATCH' is not supported"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(405));
    }

    @Test
    @DisplayName("404 无 msg 时用状态码原因短语兜底")
    void notFound() throws Exception {
        mvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("Not Found"));
    }

    @Test
    @DisplayName("错误属性缺失时按 500 处理，不再返回空响应体")
    void unknownError() throws Exception {
        mvc.perform(get("/error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("Internal Server Error"));
    }
}
