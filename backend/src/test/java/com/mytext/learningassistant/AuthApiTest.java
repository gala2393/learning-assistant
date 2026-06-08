package com.mytext.learningassistant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mytext.learningassistant.security.ShortTermStateStore;
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
/**
 * 认证接口集成测试。
 * <p>
 * 覆盖范围：用户注册、密码登录、邮箱验证码登录、个人信息查询与修改、
 * 密码修改、密码重置（邮箱验证码）、管理员控制台契约、验证码强制校验、
 * 登出令牌失效、CORS 预检放行、用户名可用性检查、旧版密码哈希兼容等。
 */
class AuthApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ShortTermStateStore stateStore;

    /**
     * 测试场景：用户注册接口。
     * 预期结果：注册成功后返回用户资料，角色为 USER，不包含管理后台访问权限，
     *           可见路由包含 /workspace/chat 但不包含 /workspace/admin。
     */
    @Test
    void registerReturnsUserProfileWithUserConsoleContract() throws Exception {
        String username = uniqueName("student");
        String email = emailFor(username);
        sendEmailCode(email);
        String code = latestEmailCode(email);

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "%s",
                      "username": "%s",
                      "password": "12345678",
                      "confirmPassword": "12345678",
                      "code": "%s"
                    }
                    """.formatted(email, username, code)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.username").value(username))
            .andExpect(jsonPath("$.data.nickname").value(username))
            .andExpect(jsonPath("$.data.role").value("USER"))
            .andExpect(jsonPath("$.data.canAccessAdminConsole").value(false))
            .andExpect(jsonPath("$.data.visibleRoutes[?(@ == '/workspace/chat')]").exists())
            .andExpect(jsonPath("$.data.visibleRoutes[?(@ == '/workspace/admin')]").doesNotExist())
            .andExpect(jsonPath("$.data.permissions[?(@ == 'ADMIN_CONSOLE')]").doesNotExist());
    }

    @Test
    void registerAcceptsSingleCharacterUsername() throws Exception {
        String username = "学";
        String email = emailFor("single_char_" + uniqueName("user"));
        sendEmailCode(email);
        String code = latestEmailCode(email);

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "%s",
                      "username": "%s",
                      "password": "12345678",
                      "confirmPassword": "12345678",
                      "code": "%s"
                    }
                    """.formatted(email, username, code)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.username").value(username));
    }

    /**
     * 测试场景：密码登录后调用 /api/auth/me 获取当前用户信息。
     * 预期结果：返回的用户资料符合普通用户控制台契约（canAccessAdminConsole=false）。
     */
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
            .andExpect(jsonPath("$.data.nickname").value(username))
            .andExpect(jsonPath("$.data.canAccessAdminConsole").value(false))
            .andExpect(jsonPath("$.data.visibleRoutes[?(@ == '/workspace/chat')]").exists())
            .andExpect(jsonPath("$.data.visibleRoutes[?(@ == '/workspace/admin')]").doesNotExist());
    }

    /**
     * 测试场景：使用邮箱地址（大写）作为用户名登录。
     * 预期结果：登录接口同时支持用户名和邮箱登录，邮箱大小写不敏感，返回有效 token。
     */
    @Test
    void loginAcceptsEmailOrUsernameWithPassword() throws Exception {
        String username = uniqueName("dual_login");
        String email = register(username, "Student");

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "%s",
                      "password": "12345678"
                    }
                    """.formatted(email.toUpperCase(Locale.ROOT))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.user.username").value(username))
            .andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    /**
     * 测试场景：邮箱验证码登录流程，包括首次登录自动创建用户及验证码防重复使用。
     * 预期结果：首次邮箱登录成功并自动创建 USER 角色账户；同一验证码第二次使用返回 400。
     */
    @Test
    void emailCodeLoginCreatesUserAndRejectsCodeReuse() throws Exception {
        String email = uniqueName("mail") + "@example.com";

        mockMvc.perform(post("/api/auth/email-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "%s",
                      "provider": "netease"
                    }
                    """.formatted(email)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        String code = latestEmailCode(email);
        var loginResult = mockMvc.perform(post("/api/auth/email-login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "%s",
                      "code": "%s"
                    }
                    """.formatted(email.toUpperCase(), code)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.token").isNotEmpty())
            .andExpect(jsonPath("$.data.user.username").value(email))
            .andExpect(jsonPath("$.data.user.role").value("USER"))
            .andReturn();

        String token = extractToken(loginResult.getResponse().getContentAsString());
        mockMvc.perform(get("/api/auth/me")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.username").value(email));

        mockMvc.perform(post("/api/auth/email-login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "%s",
                      "code": "%s"
                    }
                    """.formatted(email, code)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));
    }

    /**
     * 测试场景：修改当前用户的昵称和头像。
     * 预期结果：PUT /api/auth/me 成功后，再次查询确认昵称和头像已更新。
     */
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

    /**
     * 测试场景：修改密码需要提供正确的当前密码，且修改后旧密码失效、新密码可用。
     * 预期结果：旧密码登录返回 400，新密码登录返回有效 token。
     */
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

    /**
     * 测试场景：通过邮箱验证码重置密码。
     * 预期结果：重置成功后旧密码失效，新密码可正常登录。
     */
    @Test
    void resetPasswordWithEmailCodeAllowsNewPasswordLogin() throws Exception {
        String username = uniqueName("reset_password");
        String email = register(username, "Student");
        sendEmailCode(email);
        String code = latestEmailCode(email);

        mockMvc.perform(post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "%s",
                      "code": "%s",
                      "newPassword": "87654321",
                      "confirmPassword": "87654321"
                    }
                    """.formatted(email.toUpperCase(Locale.ROOT), code)))
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
                    """.formatted(email)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    /**
     * 测试场景：对未注册的邮箱发起密码重置。
     * 预期结果：返回 400 并提示"该邮箱未注册"，验证码未被消耗（仍可查询到相同验证码）。
     */
    @Test
    void resetPasswordRejectsUnregisteredEmailBeforeConsumingCode() throws Exception {
        String email = uniqueName("missing") + "@example.com";
        sendEmailCode(email);
        String code = latestEmailCode(email);

        mockMvc.perform(post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "%s",
                      "code": "%s",
                      "newPassword": "87654321",
                      "confirmPassword": "87654321"
                    }
                    """.formatted(email, code)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("该邮箱未注册，请先注册"));

        String storedCode = latestEmailCode(email);
        org.assertj.core.api.Assertions.assertThat(storedCode).isEqualTo(code);
    }

    /**
     * 测试场景：管理员用户调用 /api/auth/me。
     * 预期结果：返回 ADMIN 角色，canAccessAdminConsole=true，包含 /admin/dashboard 路由，
     *           拥有 ADMIN_CONSOLE 权限。
     */
    @Test
    void adminMeReturnsAdminConsoleContract() throws Exception {
        String username = uniqueName("admin");
        register(username, "Admin");
        promoteToAdmin(username);
        String token = loginWithCaptcha(username, "12345678");

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

    /**
     * 测试场景：管理员密码登录必须携带验证码。
     * 预期结果：不带验证码登录返回 428（Precondition Required），
     *           携带验证码后登录成功。
     */
    @Test
    void adminPasswordLoginRequiresCaptcha() throws Exception {
        String username = uniqueName("admin_captcha");
        register(username, "Admin");
        promoteToAdmin(username);

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "%s",
                      "password": "12345678"
                    }
                    """.formatted(username)))
            .andExpect(status().isPreconditionRequired())
            .andExpect(jsonPath("$.code").value(428));

        String token = loginWithCaptcha(username, "12345678");
        org.assertj.core.api.Assertions.assertThat(token).isNotBlank();
    }

    /**
     * 测试场景：默认管理员（admin）账户登录并查看身份信息。
     * 预期结果：默认管理员可正常登录，/api/auth/me 返回 ADMIN 角色和管理后台访问权限。
     */
    @Test
    void defaultAdminCanLoginAndMeReturnsAdminRole() throws Exception {
        var loginResult = loginWithCaptchaResult("admin", "test-admin-123456");

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

    /**
     * 测试场景：连续密码错误达到阈值后，登录接口要求输入验证码。
     * 预期结果：3 次错误密码后，正确密码登录也返回 428；
     *           携带验证码后可正常登录。
     */
    @Test
    void passwordLoginRequiresCaptchaAfterRepeatedFailures() throws Exception {
        String username = uniqueName("captcha_user");
        register(username, "Student");

        for (int i = 0; i < 3; i += 1) {
            mockMvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "username": "%s",
                          "password": "wrong-password"
                        }
                        """.formatted(username)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        }

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "%s",
                      "password": "12345678"
                    }
                    """.formatted(username)))
            .andExpect(status().isPreconditionRequired())
            .andExpect(jsonPath("$.code").value(428));

        String token = loginWithCaptcha(username, "12345678");
        mockMvc.perform(get("/api/auth/me")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.username").value(username));
    }

    /**
     * 测试场景：未携带 token 访问受保护接口。
     * 预期结果：返回 401 Unauthorized。
     */
    @Test
    void protectedEndpointRequiresToken() throws Exception {
        mockMvc.perform(get("/api/materials"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));
    }

    /**
     * 测试场景：用户登出后 token 在服务端被注销。
     * 预期结果：登出接口返回成功，使用已登出的 token 访问 /api/auth/me 返回 401。
     */
    @Test
    void logoutInvalidatesCurrentTokenOnServer() throws Exception {
        String username = uniqueName("logout");
        register(username, "Student");
        String token = login(username);

        mockMvc.perform(post("/api/auth/logout")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/auth/me")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));
    }

    /**
     * 测试场景：CORS 预检请求（OPTIONS）应绕过认证拦截器。
     * 预期结果：OPTIONS /api/auth/me 返回 200 OK。
     */
    @Test
    void corsPreflightBypassesAuthentication() throws Exception {
        mockMvc.perform(options("/api/auth/me")
                .header("Origin", "http://127.0.0.1:15174")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization"))
            .andExpect(status().isOk());
    }

    /**
     * 测试场景：注册前检查用户名是否可用。
     * 预期结果：已注册的用户名返回 available=false，未注册的用户名返回 available=true。
     */
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

    /**
     * 测试场景：注册时用户名前后带空格，去除空格后与已注册用户名相同。
     * 预期结果：返回 400，拒绝重复用户名注册。
     */
    @Test
    void registerRejectsExistingUsernameAfterTrimmingInput() throws Exception {
        String username = uniqueName("trim_user");
        register(username, "Student");

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "%s",
                      "username": "  %s  ",
                      "password": "12345678",
                      "confirmPassword": "12345678",
                      "code": "000000"
                    }
                    """.formatted(emailFor("other_" + username), username)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));
    }

    /**
     * 测试场景：数据库中存储了格式损坏的密码哈希。
     * 预期结果：登录返回 400（而非 500 服务器错误），系统能优雅处理异常哈希。
     */
    @Test
    void loginReturnsBadRequestInsteadOfServerErrorForMalformedStoredPasswordHash() throws Exception {
        String username = uniqueName("broken_hash");
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setEmail(emailFor(username));
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

    /**
     * 测试场景：使用旧版 SHA-256 密码哈希格式的用户登录后自动升级为新格式。
     * 预期结果：登录成功返回有效 token，/api/auth/me 正常返回用户信息。
     */
    @Test
    void tokenReturnedDuringLegacyPasswordUpgradeRemainsValid() throws Exception {
        String username = uniqueName("legacy_hash");
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setEmail(emailFor(username));
        user.setNickname("Legacy Hash");
        user.setPasswordHash(legacySha256Hash("12345678"));
        user.setRole(UserRole.USER);
        user.setStatus(com.mytext.learningassistant.user.UserStatus.ACTIVE);
        userRepository.save(user);

        String token = login(username);

        mockMvc.perform(get("/api/auth/me")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.username").value(username));
    }

    // ========== 辅助方法 ==========

    /** 注册一个新用户（发送邮箱验证码并提交注册表单），返回注册邮箱 */
    private String register(String username, String nickname) throws Exception {
        String email = emailFor(username);
        sendEmailCode(email);
        String code = latestEmailCode(email);
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "%s",
                      "username": "%s",
                      "password": "12345678",
                      "confirmPassword": "12345678",
                      "code": "%s"
                    }
                    """.formatted(email, username, code)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));
        return email;
    }

    /** 使用用户名和默认密码登录，返回 token 字符串 */
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

    /** 使用验证码登录，返回 token 字符串 */
    private String loginWithCaptcha(String username, String password) throws Exception {
        return extractToken(loginWithCaptchaResult(username, password).getResponse().getContentAsString());
    }

    /** 使用验证码登录，返回完整 MvcResult（包含响应体） */
    private org.springframework.test.web.servlet.MvcResult loginWithCaptchaResult(String username, String password) throws Exception {
        String challengeId = createLoginCaptcha(username);
        String captchaCode = latestLoginCaptchaCode(challengeId);
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "%s",
                      "password": "%s",
                      "captchaChallengeId": "%s",
                      "captchaCode": "%s"
                    }
                    """.formatted(username, password, challengeId, captchaCode)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.token").isNotEmpty())
            .andReturn();
    }

    /** 调用登录验证码接口，返回 challengeId */
    private String createLoginCaptcha(String username) throws Exception {
        var result = mockMvc.perform(post("/api/auth/login-captcha")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "%s"
                    }
                    """.formatted(username)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.challengeId").isNotEmpty())
            .andExpect(jsonPath("$.data.imageDataUrl").isNotEmpty())
            .andReturn();
        return extractChallengeId(result.getResponse().getContentAsString());
    }

    /** 将指定用户提升为管理员角色 */
    private void promoteToAdmin(String username) {
        UserEntity user = userRepository.findByUsername(username).orElseThrow();
        user.setRole(UserRole.ADMIN);
        userRepository.save(user);
    }

    /** 生成唯一用户名，避免测试之间冲突 */
    private String uniqueName(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** 根据用户名生成测试邮箱地址 */
    private String emailFor(String username) {
        return username.replace("_", "") + "@example.com";
    }

    /** 调用邮箱验证码发送接口 */
    private void sendEmailCode(String email) throws Exception {
        mockMvc.perform(post("/api/auth/email-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "email": "%s",
                      "provider": "qq"
                    }
                    """.formatted(email)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));
    }

    /** 通过短期状态存储获取最新的邮箱验证码 */
    private String latestEmailCode(String email) {
        String code = stateStore.get("email:code:" + email.toLowerCase(Locale.ROOT));
        if (code == null) {
            throw new IllegalStateException("email code not found for " + email);
        }
        return code;
    }

    /** 通过短期状态存储获取指定 challengeId 的登录验证码 */
    private String latestLoginCaptchaCode(String challengeId) {
        String challenge = stateStore.get("login:captcha:challenge:" + challengeId);
        if (challenge == null) {
            throw new IllegalStateException("login captcha not found for " + challengeId);
        }
        int separator = challenge.indexOf('\n');
        if (separator < 0 || separator == challenge.length() - 1) {
            throw new IllegalStateException("login captcha code malformed for " + challengeId);
        }
        return challenge.substring(separator + 1);
    }

    /** 生成旧版 SHA-256 密码哈希（salt:digest 格式），用于测试密码迁移兼容性 */
    private String legacySha256Hash(String password) throws Exception {
        byte[] salt = ("salt-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
        messageDigest.update(salt);
        byte[] digest = messageDigest.digest(password.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(digest);
    }

    /** 从 JSON 响应体中提取 challengeId 字段 */
    private String extractChallengeId(String body) {
        Matcher matcher = Pattern.compile("\"challengeId\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (!matcher.find()) {
            throw new IllegalStateException("challengeId not found in response: " + body);
        }
        return matcher.group(1);
    }

    /** 从 JSON 响应体中提取 token 字段 */
    private String extractToken(String body) {
        Matcher matcher = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (!matcher.find()) {
            throw new IllegalStateException("token not found in response: " + body);
        }
        return matcher.group(1);
    }
}
