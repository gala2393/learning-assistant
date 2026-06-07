package com.mytext.learningassistant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HealthController.class)
/**
 * 健康检查控制器单元测试。
 * <p>
 * 覆盖范围：/api/health 和根路径 / 的健康状态接口，验证返回的服务名称和状态信息。
 * 使用 @WebMvcTest 仅加载控制器层，不启动完整 Spring 上下文。
 */
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * 测试场景：GET /api/health 健康检查接口。
     * 预期结果：返回 200，status 为 "ok"，service 为 "智学引擎"。
     */
    @Test
    void returnsApiStatus() throws Exception {
        mockMvc.perform(get("/api/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.service").value("智学引擎"));
    }

    /**
     * 测试场景：GET / 根路径健康检查接口。
     * 预期结果：返回 200，包含 status、service 和 message 字段。
     */
    @Test
    void returnsRootStatus() throws Exception {
        mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.service").value("智学引擎"))
            .andExpect(jsonPath("$.message").value("backend is running"));
    }
}
