package com.mytext.learningassistant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mytext.learningassistant.user.UserEntity;
import com.mytext.learningassistant.user.UserRepository;
import com.mytext.learningassistant.user.UserRole;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AuthApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Test
    void registerReturnsUserProfileWithUserConsoleContract() throws Exception {
        String username = uniqueName("student");

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "%s",
                      "password": "12345678",
                      "nickname": "Student"
                    }
                    """.formatted(username)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.username").value(username))
            .andExpect(jsonPath("$.data.nickname").value("Student"))
            .andExpect(jsonPath("$.data.role").value("USER"))
            .andExpect(jsonPath("$.data.canAccessAdminConsole").value(false))
            .andExpect(jsonPath("$.data.visibleRoutes[?(@ == '/workspace/chat')]").exists())
            .andExpect(jsonPath("$.data.visibleRoutes[?(@ == '/workspace/admin')]").doesNotExist())
            .andExpect(jsonPath("$.data.permissions[?(@ == 'ADMIN_CONSOLE')]").doesNotExist());
    }

    @Test
    void loginReturnsTokenAndMeReturnsCurrentUserConsoleContract() throws Exception {
        String username = uniqueName("student");
        register(username, "Student");
        String token = login(username);

        mockMvc.perform(get("/api/auth/me")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.username").value(username))
            .andExpect(jsonPath("$.data.nickname").value("Student"))
            .andExpect(jsonPath("$.data.canAccessAdminConsole").value(false))
            .andExpect(jsonPath("$.data.visibleRoutes[?(@ == '/workspace/chat')]").exists())
            .andExpect(jsonPath("$.data.visibleRoutes[?(@ == '/workspace/admin')]").doesNotExist());
    }

    @Test
    void updateMeChangesNicknameAndAvatar() throws Exception {
        String username = uniqueName("profile");
        register(username, "Student");
        String token = login(username);

        mockMvc.perform(put("/api/auth/me")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "nickname": "Updated Student",
                      "avatar": "preset:forest"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.username").value(username))
            .andExpect(jsonPath("$.data.nickname").value("Updated Student"))
            .andExpect(jsonPath("$.data.avatar").value("preset:forest"));

        mockMvc.perform(get("/api/auth/me")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.nickname").value("Updated Student"))
            .andExpect(jsonPath("$.data.avatar").value("preset:forest"));
    }

    @Test
    void updatePasswordRequiresCurrentPasswordAndAllowsNewLogin() throws Exception {
        String username = uniqueName("password");
        register(username, "Student");
        String token = login(username);

        mockMvc.perform(put("/api/auth/password")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "currentPassword": "wrong-password",
                      "newPassword": "87654321",
                      "confirmPassword": "87654321"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(put("/api/auth/password")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "currentPassword": "12345678",
                      "newPassword": "87654321",
                      "confirmPassword": "87654321"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "%s",
                      "password": "12345678"
                    }
                    """.formatted(username)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "%s",
                      "password": "87654321"
                    }
                    """.formatted(username)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    @Test
    void adminMeReturnsAdminConsoleContract() throws Exception {
        String username = uniqueName("admin");
        register(username, "Admin");
        promoteToAdmin(username);
        String token = login(username);

        mockMvc.perform(get("/api/auth/me")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.role").value("ADMIN"))
            .andExpect(jsonPath("$.data.canAccessAdminConsole").value(true))
            .andExpect(jsonPath("$.data.visibleRoutes[?(@ == '/admin/dashboard')]").exists())
            .andExpect(jsonPath("$.data.visibleRoutes[?(@ == '/workspace/admin')]").doesNotExist())
            .andExpect(jsonPath("$.data.permissions[?(@ == 'ADMIN_CONSOLE')]").exists());
    }

    @Test
    void defaultAdminCanLoginAndMeReturnsAdminRole() throws Exception {
        var loginResult = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "admin",
                      "password": "12345678"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.user.username").value("admin"))
            .andExpect(jsonPath("$.data.user.role").value("ADMIN"))
            .andExpect(jsonPath("$.data.token").isNotEmpty())
            .andReturn();

        String token = extractToken(loginResult.getResponse().getContentAsString());

        mockMvc.perform(get("/api/auth/me")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.username").value("admin"))
            .andExpect(jsonPath("$.data.role").value("ADMIN"))
            .andExpect(jsonPath("$.data.canAccessAdminConsole").value(true))
            .andExpect(jsonPath("$.data.visibleRoutes[?(@ == '/admin/dashboard')]").exists())
            .andExpect(jsonPath("$.data.visibleRoutes[?(@ == '/workspace/admin')]").doesNotExist());
    }

    @Test
    void protectedEndpointRequiresToken() throws Exception {
        mockMvc.perform(get("/api/materials"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void corsPreflightBypassesAuthentication() throws Exception {
        mockMvc.perform(options("/api/auth/me")
                .header("Origin", "http://127.0.0.1:15174")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization"))
            .andExpect(status().isOk());
    }

    @Test
    void checkUsernameReportsAvailabilityBeforeRegisterSubmit() throws Exception {
        String username = uniqueName("check_user");
        register(username, "Student");

        mockMvc.perform(get("/api/auth/check-username")
                .param("username", username))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.username").value(username))
            .andExpect(jsonPath("$.data.available").value(false));

        mockMvc.perform(get("/api/auth/check-username")
                .param("username", username + "_new"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.available").value(true));
    }

    @Test
    void registerRejectsExistingUsernameAfterTrimmingInput() throws Exception {
        String username = uniqueName("trim_user");
        register(username, "Student");

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "  %s  ",
                      "password": "12345678",
                      "nickname": "Student"
                    }
                    """.formatted(username)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void loginReturnsBadRequestInsteadOfServerErrorForMalformedStoredPasswordHash() throws Exception {
        String username = uniqueName("broken_hash");
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setNickname("Broken Hash");
        user.setPasswordHash("not-a-valid-base64-hash");
        user.setRole(UserRole.USER);
        user.setStatus(com.mytext.learningassistant.user.UserStatus.ACTIVE);
        userRepository.save(user);

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "%s",
                      "password": "12345678"
                    }
                    """.formatted(username)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));
    }

    private void register(String username, String nickname) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "%s",
                      "password": "12345678",
                      "nickname": "%s"
                    }
                    """.formatted(username, nickname)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));
    }

    private String login(String username) throws Exception {
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
        return extractToken(loginResult.getResponse().getContentAsString());
    }

    private void promoteToAdmin(String username) {
        UserEntity user = userRepository.findByUsername(username).orElseThrow();
        user.setRole(UserRole.ADMIN);
        userRepository.save(user);
    }

    private String uniqueName(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String extractToken(String body) {
        Matcher matcher = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (!matcher.find()) {
            throw new IllegalStateException("token not found in response: " + body);
        }
        return matcher.group(1);
    }
}
