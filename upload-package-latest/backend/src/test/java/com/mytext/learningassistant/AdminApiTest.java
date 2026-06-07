package com.mytext.learningassistant;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mytext.learningassistant.user.UserEntity;
import com.mytext.learningassistant.user.UserRepository;
import com.mytext.learningassistant.user.UserRole;
import com.mytext.learningassistant.admin.SystemLogEntity;
import com.mytext.learningassistant.admin.SystemLogRepository;
import com.mytext.learningassistant.rag.QuestionStatus;
import com.mytext.learningassistant.rag.RagQuestionEntity;
import com.mytext.learningassistant.rag.RagQuestionRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AdminApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SystemLogRepository systemLogRepository;

    @Autowired
    private RagQuestionRepository ragQuestionRepository;

    @Test
    void userAndAnonymousRequestsCannotAccessAdminEndpoints() throws Exception {
        String userUsername = uniqueName("student");
        String userToken = registerAndLogin(userUsername);
        Long userId = userRepository.findByUsername(userUsername).orElseThrow().getId();
        Long materialId = uploadTextMaterial(userToken);

        mockMvc.perform(get("/api/admin/stats"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(get("/api/admin/stats")
                .header("Authorization", "Bearer " + userToken))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(get("/api/admin/users")
                .header("Authorization", "Bearer " + userToken))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(get("/api/admin/materials")
                .header("Authorization", "Bearer " + userToken))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(get("/api/admin/logs")
                .header("Authorization", "Bearer " + userToken))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(patch("/api/admin/users/{id}/role", userId)
                .header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "role": "ADMIN"
                    }
                    """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(patch("/api/admin/materials/{id}/status", materialId)
                .header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "parseStatus": "FAILED",
                      "summaryStatus": "FAILED"
                    }
                    """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void adminCanManageUsersMaterialsAndReadLogs() throws Exception {
        String adminUsername = uniqueName("admin");
        String adminToken = registerAndLogin(adminUsername);
        promoteToAdmin(adminUsername);

        String userUsername = uniqueName("student");
        String userToken = registerAndLogin(userUsername);
        Long userId = userRepository.findByUsername(userUsername).orElseThrow().getId();
        Long materialId = uploadTextMaterial(userToken);

        mockMvc.perform(get("/api/admin/users")
                .header("Authorization", "Bearer " + userToken))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(get("/api/admin/stats")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.userCount", greaterThanOrEqualTo(2)))
            .andExpect(jsonPath("$.data.materialCount", greaterThanOrEqualTo(1)));

        mockMvc.perform(get("/api/admin/users")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[*].username", hasItem(userUsername)))
            .andExpect(jsonPath("$.data.items[*].role", hasItem("USER")))
            .andExpect(jsonPath("$.data.total", greaterThanOrEqualTo(2)));

        mockMvc.perform(get("/api/admin/users")
                .header("Authorization", "Bearer " + adminToken)
                .param("keyword", userUsername)
                .param("page", "0")
                .param("size", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].username").value(userUsername))
            .andExpect(jsonPath("$.data.page").value(0))
            .andExpect(jsonPath("$.data.size").value(1));

        mockMvc.perform(patch("/api/admin/users/{id}/role", userId)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "role": "ADMIN"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(userId.intValue()))
            .andExpect(jsonPath("$.data.role").value("ADMIN"));

        mockMvc.perform(patch("/api/admin/users/{id}/status", userId)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "status": "DISABLED"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(userId.intValue()))
            .andExpect(jsonPath("$.data.status").value("DISABLED"));

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "%s",
                      "password": "12345678"
                    }
                    """.formatted(userUsername)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("该账户已禁用，请联系管理员解除封禁。"));

        mockMvc.perform(patch("/api/admin/users/{id}/status", userId)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "status": "ACTIVE"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        Long adminId = userRepository.findByUsername(adminUsername).orElseThrow().getId();
        mockMvc.perform(patch("/api/admin/users/{id}/status", adminId)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "status": "DISABLED"
                    }
                    """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(patch("/api/admin/users/{id}/role", adminId)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "role": "USER"
                    }
                    """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(get("/api/admin/materials")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[*].id", hasItem(materialId.intValue())))
            .andExpect(jsonPath("$.data.items[*].parseStatus", hasItem("SUCCESS")))
            .andExpect(jsonPath("$.data.total", greaterThanOrEqualTo(1)));

        mockMvc.perform(get("/api/admin/materials")
                .header("Authorization", "Bearer " + adminToken)
                .param("keyword", "Admin queue")
                .param("page", "0")
                .param("size", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].id").value(materialId.intValue()))
            .andExpect(jsonPath("$.data.page").value(0))
            .andExpect(jsonPath("$.data.size").value(1));

        mockMvc.perform(patch("/api/admin/materials/{id}/status", materialId)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "parseStatus": "FAILED",
                      "summaryStatus": "FAILED"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(materialId.intValue()))
            .andExpect(jsonPath("$.data.parseStatus").value("FAILED"))
            .andExpect(jsonPath("$.data.summaryStatus").value("FAILED"));

        mockMvc.perform(get("/api/admin/logs")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[*].action", hasItem("UPDATE_USER_ROLE")))
            .andExpect(jsonPath("$.data.items[*].action", hasItem("UPDATE_USER_STATUS")))
            .andExpect(jsonPath("$.data.items[*].action", hasItem("UPDATE_MATERIAL_STATUS")))
            .andExpect(jsonPath("$.data.total", greaterThanOrEqualTo(2)));

        mockMvc.perform(get("/api/admin/logs")
                .header("Authorization", "Bearer " + adminToken)
                .param("keyword", "MATERIAL")
                .param("page", "0")
                .param("size", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].action").value("UPDATE_MATERIAL_STATUS"))
            .andExpect(jsonPath("$.data.page").value(0))
            .andExpect(jsonPath("$.data.size").value(1));
    }

    @Test
    void usageActionsAreShownInUsageRecordsNotSystemLogs() throws Exception {
        String adminUsername = uniqueName("usage-admin");
        String adminToken = registerAndLogin(adminUsername);
        promoteToAdmin(adminUsername);
        Long adminId = userRepository.findByUsername(adminUsername).orElseThrow().getId();

        SystemLogEntity oldUsageLog = new SystemLogEntity();
        oldUsageLog.setActorUserId(adminId);
        oldUsageLog.setAction("RAG_CHAT_STREAM");
        oldUsageLog.setTargetType("RAG_QUESTION");
        oldUsageLog.setTargetId(999L);
        oldUsageLog.setDetail("model=mimo-v2.5-pro, customModel=true, promptTokens=2, completionTokens=9, totalTokens=11");
        systemLogRepository.save(oldUsageLog);

        mockMvc.perform(get("/api/admin/logs")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[?(@.action=='RAG_CHAT_STREAM')]").isEmpty());

        mockMvc.perform(get("/api/admin/usage-records")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[?(@.action=='RAG_CHAT_STREAM')].username", hasItem(adminUsername)))
            .andExpect(jsonPath("$.data.items[?(@.action=='RAG_CHAT_STREAM')].modelName", hasItem("mimo-v2.5-pro")))
            .andExpect(jsonPath("$.data.items[?(@.action=='RAG_CHAT_STREAM')].totalTokens", hasItem(11)));
    }

    @Test
    void ragQuestionHistoryIsVisibleInUsageRecordsEvenWithoutUsageRecordRow() throws Exception {
        String adminUsername = uniqueName("history-usage-admin");
        String adminToken = registerAndLogin(adminUsername);
        promoteToAdmin(adminUsername);
        Long adminId = userRepository.findByUsername(adminUsername).orElseThrow().getId();

        RagQuestionEntity question = new RagQuestionEntity();
        question.setUserId(adminId);
        question.setQuestionText("usage record fallback question");
        question.setAnswerText("usage record fallback answer");
        question.setModelName("mimo-v2.5-pro");
        question.setPromptTokens(12);
        question.setCompletionTokens(18);
        question.setTotalTokens(30);
        question.setCustomModel(true);
        question.setQuestionStatus(QuestionStatus.SUCCESS);
        ragQuestionRepository.save(question);

        mockMvc.perform(get("/api/admin/usage-records")
                .header("Authorization", "Bearer " + adminToken)
                .param("keyword", "mimo-v2.5-pro"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[?(@.targetId==" + question.getId() + ")].username", hasItem(adminUsername)))
            .andExpect(jsonPath("$.data.items[?(@.targetId==" + question.getId() + ")].modelName", hasItem("mimo-v2.5-pro")))
            .andExpect(jsonPath("$.data.items[?(@.targetId==" + question.getId() + ")].totalTokens", hasItem(30)));
    }

    @Test
    void adminCannotDemoteOwnAccount() throws Exception {
        String adminUsername = uniqueName("self-admin");
        String adminToken = registerAndLogin(adminUsername);
        promoteToAdmin(adminUsername);
        Long adminId = userRepository.findByUsername(adminUsername).orElseThrow().getId();

        mockMvc.perform(patch("/api/admin/users/{id}/role", adminId)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "role": "USER"
                    }
                    """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(get("/api/admin/stats")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void adminPagesHandleExtremePageNumbers() throws Exception {
        String adminUsername = uniqueName("page-admin");
        String adminToken = registerAndLogin(adminUsername);
        promoteToAdmin(adminUsername);

        mockMvc.perform(get("/api/admin/users")
                .header("Authorization", "Bearer " + adminToken)
                .param("page", String.valueOf(Integer.MAX_VALUE))
                .param("size", "100"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(0))
            .andExpect(jsonPath("$.data.page").value(Integer.MAX_VALUE))
            .andExpect(jsonPath("$.data.size").value(100));

        mockMvc.perform(get("/api/admin/materials")
                .header("Authorization", "Bearer " + adminToken)
                .param("page", String.valueOf(Integer.MAX_VALUE))
                .param("size", "100"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(0))
            .andExpect(jsonPath("$.data.page").value(Integer.MAX_VALUE))
            .andExpect(jsonPath("$.data.size").value(100));

        mockMvc.perform(get("/api/admin/logs")
                .header("Authorization", "Bearer " + adminToken)
                .param("page", String.valueOf(Integer.MAX_VALUE))
                .param("size", "100"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(0))
            .andExpect(jsonPath("$.data.page").value(Integer.MAX_VALUE))
            .andExpect(jsonPath("$.data.size").value(100));
    }

    private Long uploadTextMaterial(String token) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "admin-note.txt",
            MediaType.TEXT_PLAIN_VALUE,
            "Admin queue material for management.".getBytes(StandardCharsets.UTF_8)
        );

        var result = mockMvc.perform(multipart("/api/materials")
                .file(file)
                .header("Authorization", "Bearer " + token)
                .param("title", "Admin queue note")
                .param("sourceType", "TXT"))
            .andExpect(status().isOk())
            .andReturn();

        return extractLong(result.getResponse().getContentAsString(), "id");
    }

    private void promoteToAdmin(String username) {
        UserEntity user = userRepository.findByUsername(username).orElseThrow();
        user.setRole(UserRole.ADMIN);
        userRepository.save(user);
    }

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "%s",
                      "password": "12345678",
                      "nickname": "Admin Test User"
                    }
                    """.formatted(username)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        var loginResult = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "%s",
                      "password": "12345678"
                    }
                    """.formatted(username)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.token").isNotEmpty())
            .andReturn();

        return extractString(loginResult.getResponse().getContentAsString(), "token");
    }

    private String uniqueName(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String extractString(String body, String fieldName) {
        Matcher matcher = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (!matcher.find()) {
            throw new IllegalStateException(fieldName + " not found in response: " + body);
        }
        return matcher.group(1);
    }

    private Long extractLong(String body, String fieldName) {
        Matcher matcher = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*(\\d+)").matcher(body);
        if (!matcher.find()) {
            throw new IllegalStateException(fieldName + " not found in response: " + body);
        }
        return Long.parseLong(matcher.group(1));
    }
}
