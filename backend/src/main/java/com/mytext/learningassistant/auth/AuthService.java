package com.mytext.learningassistant.auth;

import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.mytext.learningassistant.common.BusinessException;
import com.mytext.learningassistant.user.UserEntity;
import com.mytext.learningassistant.user.UserRepository;
import com.mytext.learningassistant.user.UserRole;
import com.mytext.learningassistant.user.UserStatus;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证业务服务 — 处理用户注册、登录、登出、密码管理、个人资料修改等核心业务逻辑。
 * <p>
 * 本类是认证模块的核心，由 {@link AuthController} 调用，协调以下组件完成业务：
 * <ul>
 *   <li>{@link UserRepository} — 用户数据持久化</li>
 *   <li>{@link PasswordHasher} — 密码哈希和验证</li>
 *   <li>{@link TokenService} — JWT Token 的生成和解析</li>
 *   <li>{@link EmailCodeService} — 邮箱验证码的发送和验证</li>
 *   <li>{@link LoginCaptchaService} — 登录验证码（防暴力破解）</li>
 * </ul>
 */
@Service
public class AuthService {

    /** 日期时间格式化器，用于将 LocalDateTime 格式化为 "yyyy-MM-dd HH:mm:ss" 字符串 */
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 账户被禁用时的统一提示信息 */
    private static final String ACCOUNT_DISABLED_MESSAGE = "该账户已禁用，请联系管理员解除封禁。";

    /** 用户数据仓库，用于数据库 CRUD 操作 */
    private final UserRepository userRepository;

    /** 密码哈希工具，负责密码加密和验证 */
    private final PasswordHasher passwordHasher;

    /** Token 服务，负责生成和解析 JWT Token */
    private final TokenService tokenService;

    /** 邮箱验证码服务，负责发送和验证邮箱验证码 */
    private final EmailCodeService emailCodeService;

    /** 登录验证码服务，负责防暴力破解的图形验证码 */
    private final LoginCaptchaService loginCaptchaService;

    /**
     * 构造方法，通过依赖注入获取所需组件。
     *
     * @param userRepository     用户数据仓库
     * @param passwordHasher     密码哈希工具
     * @param tokenService       Token 服务
     * @param emailCodeService   邮箱验证码服务
     * @param loginCaptchaService 登录验证码服务
     */
    public AuthService(
        UserRepository userRepository,
        PasswordHasher passwordHasher,
        TokenService tokenService,
        EmailCodeService emailCodeService,
        LoginCaptchaService loginCaptchaService
    ) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.tokenService = tokenService;
        this.emailCodeService = emailCodeService;
        this.loginCaptchaService = loginCaptchaService;
    }

    /**
     * 用户注册 — 创建新用户账号。
     * <p>
     * 业务规则：
     * <ul>
     *   <li>用户名必填且不能重复</li>
     *   <li>如果提供了邮箱，需要先通过验证码验证，且邮箱不能已注册</li>
     *   <li>如果提供了确认密码，必须与密码一致</li>
     *   <li>密码由 PasswordHasher 进行哈希加密后存储</li>
     * </ul>
     *
     * @param request 注册请求（包含用户名、密码、邮箱、验证码等）
     * @return 注册成功后的用户信息（不含密码）
     * @throws BusinessException 用户名已存在、邮箱已注册、验证码错误等情况
     */
    @Transactional
    public AuthUserResponse register(RegisterRequest request) {
        // 规范化输入：去除首尾空格
        String username = normalizeRequired(request.username(), "请输入用户名");
        String email = normalizeEmail(request.email());

        // 如果提供了确认密码，检查两次密码是否一致
        if (request.confirmPassword() != null && !request.password().equals(request.confirmPassword())) {
            throw new BusinessException(400, "两次输入的密码不一致");
        }
        // 如果提供了邮箱，验证邮箱验证码
        if (email != null && !emailCodeService.verify(email, request.code())) {
            // 邮箱注册必须先验证邮箱所有权，避免绑定他人邮箱。
            throw new BusinessException(400, "验证码错误或已过期");
        }
        // 检查用户名是否已被占用
        if (userRepository.existsByUsername(username)) {
            throw new BusinessException(400, "用户名已存在");
        }
        // 检查邮箱是否已被注册
        if (email != null && userRepository.existsByEmail(email)) {
            throw new BusinessException(400, "邮箱已注册，请直接登录");
        }

        // 创建新用户实体并保存到数据库
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordHasher.hash(request.password()));  // 密码哈希加密
        user.setNickname(defaultNickname(request.nickname(), username)); // 昵称默认为用户名
        user.setRole(UserRole.USER);       // 默认角色为普通用户
        user.setStatus(UserStatus.ACTIVE); // 默认状态为正常
        return toResponse(userRepository.save(user));
    }

    /**
     * 发送邮箱验证码 — 委托给 EmailCodeService 处理。
     *
     * @param request   包含邮箱地址和邮件服务商的请求
     * @param ipAddress 客户端 IP 地址，用于频率限制
     */
    public void sendEmailCode(EmailCodeRequest request, String ipAddress) {
        emailCodeService.sendCode(request.email(), request.provider(), ipAddress);
    }

    /**
     * 邮箱验证码登录 — 使用邮箱验证码登录，如果邮箱未注册则自动创建账号。
     * <p>
     * 这是一种"无密码"登录方式，用户只需提供邮箱和收到的验证码即可。
     *
     * @param request 包含邮箱和验证码的请求
     * @return 登录成功后的 Token 和用户信息
     * @throws BusinessException 验证码错误、账户被禁用等情况
     */
    @Transactional
    public LoginResponse emailLogin(EmailLoginRequest request) {
        String email = normalizeEmail(request.email());
        // 验证邮箱验证码
        if (!emailCodeService.verify(email, request.code())) {
            // 邮箱验证码登录以验证码作为唯一凭证，校验失败不创建账号。
            throw new BusinessException(400, "验证码错误或已过期");
        }
        // 查找已有用户，不存在则自动创建
        UserEntity user = userRepository.findByEmail(email)
            .orElseGet(() -> userRepository.save(createEmailUser(email)));

        // 检查账户状态
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(400, ACCOUNT_DISABLED_MESSAGE);
        }
        // 生成 Token 并返回登录响应
        return new LoginResponse(tokenService.createToken(user), toResponse(user));
    }

    /**
     * 通过邮箱验证码重置密码 — 用户忘记密码时使用。
     * <p>
     * 重置密码成功后，会递增 tokenVersion 使所有旧 Token 失效。
     *
     * @param request 包含邮箱、验证码、新密码和确认密码的请求
     * @throws BusinessException 邮箱未注册、验证码错误、两次密码不一致等情况
     */
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        String email = normalizeEmail(request.email());
        // 检查两次新密码是否一致
        if (!request.newPassword().equals(request.confirmPassword())) {
            throw new BusinessException(400, "两次输入的密码不一致");
        }
        // 查找用户
        UserEntity user = userRepository.findByEmail(email)
            .orElseThrow(() -> new BusinessException(400, "该邮箱未注册，请先注册"));
        // 验证邮箱验证码
        if (!emailCodeService.verify(email, request.code())) {
            // 重置密码必须在确认邮箱所有权后执行，防止未授权改密。
            throw new BusinessException(400, "验证码错误或已过期");
        }
        user.incrementTokenVersion();  // 递增 Token 版本号，使旧 Token 失效
        user.setPasswordHash(passwordHasher.hash(request.newPassword()));  // 设置新密码
        userRepository.save(user);
    }

    /**
     * 创建登录验证码 — 当登录失败次数过多时，需要用户输入图形验证码。
     *
     * @param request   包含用户名的请求
     * @param ipAddress 客户端 IP 地址
     * @return 验证码图片（Base64）和挑战 ID
     */
    @Transactional
    public LoginCaptchaResponse createLoginCaptcha(LoginCaptchaRequest request, String ipAddress) {
        String loginId = normalizeRequired(request.username(), "请输入用户名或邮箱");
        return loginCaptchaService.createChallenge(loginAttemptKey(ipAddress, loginId));
    }

    /**
     * 用户名/邮箱 + 密码登录 — 传统的账号密码登录方式。
     * <p>
     * 安全机制：
     * <ul>
     *   <li>登录失败次数过多时，会要求输入验证码（防暴力破解）</li>
     *   <li>密码使用 BCrypt 加密存储，自动检测旧的 SHA-256 哈希并升级</li>
     *   <li>登录成功后清除失败计数</li>
     * </ul>
     *
     * @param request   登录请求（包含用户名/邮箱、密码、可选验证码）
     * @param ipAddress 客户端 IP 地址
     * @return 登录成功后的 Token 和用户信息
     * @throws BusinessException 用户名或密码错误、账户被禁用等情况
     */
    @Transactional
    public LoginResponse login(LoginRequest request, String ipAddress) {
        String loginId = normalizeRequired(request.username(), "请输入用户名或邮箱");
        String attemptKey = loginAttemptKey(ipAddress, loginId);

        // 先尝试查找用户（支持用户名或邮箱登录）
        UserEntity user = findByUsernameOrEmail(loginId).orElse(null);
        if (user == null) {
            // 用户不存在时，如果需要验证码则先验证
            if (loginCaptchaService.requiresCaptcha(attemptKey, null)) {
                loginCaptchaService.verifyRequired(attemptKey, request);
            }
            loginCaptchaService.recordFailure(attemptKey);  // 记录失败次数
            throw new BusinessException(400, "用户名、邮箱或密码错误");
        }

        // 检查账户状态
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(400, ACCOUNT_DISABLED_MESSAGE);
        }
        // 检查是否需要验证码（失败次数过多时触发）
        if (loginCaptchaService.requiresCaptcha(attemptKey, user)) {
            loginCaptchaService.verifyRequired(attemptKey, request);
        }
        // 验证密码
        if (!passwordHasher.matches(request.password(), user.getPasswordHash())) {
            // 密码错误只返回统一错误文案，避免暴露账号是否存在或密码状态。
            loginCaptchaService.recordFailure(attemptKey);  // 密码错误，记录失败次数
            throw new BusinessException(400, "用户名、邮箱或密码错误");
        }

        // 登录成功，生成 Token
        String token = tokenService.createToken(user);

        // 如果密码哈希使用的是旧算法（SHA-256），自动升级为 BCrypt
        if (passwordHasher.needsRehash(user.getPasswordHash())) {
            // 成功登录时顺手升级旧哈希，不额外打断用户流程。
            user.setPasswordHash(passwordHasher.hash(request.password()));
            user = userRepository.save(user);
        }

        loginCaptchaService.clearFailures(attemptKey);  // 登录成功，清除失败计数
        return new LoginResponse(token, toResponse(user));
    }

    /**
     * 检查用户名是否可用 — 只读操作，不修改数据库。
     *
     * @param username 要检查的用户名
     * @return 包含用户名、是否可用、提示信息的响应
     */
    @Transactional(readOnly = true)
    public UsernameAvailabilityResponse checkUsername(String username) {
        String normalized = username == null ? "" : username.trim();
        if (normalized.isBlank()) {
            return new UsernameAvailabilityResponse(normalized, false, "请输入用户名");
        }
        boolean available = !userRepository.existsByUsername(normalized);
        return new UsernameAvailabilityResponse(
            normalized,
            available,
            available ? "用户名可用" : "用户名已存在，请重新取名"
        );
    }

    /**
     * 获取当前登录用户信息 — 只读操作。
     *
     * @param userId 用户 ID
     * @return 用户信息（不含密码）
     * @throws BusinessException 用户不存在时
     */
    @Transactional(readOnly = true)
    public AuthUserResponse me(long userId) {
        return toResponse(userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(401, "用户不存在")));
    }

    /**
     * 修改个人资料（昵称、头像）。
     *
     * @param userId  用户 ID
     * @param request 包含新昵称和头像的请求
     * @return 更新后的用户信息
     * @throws BusinessException 用户不存在或昵称为空时
     */
    @Transactional
    public AuthUserResponse updateMe(long userId, UpdateProfileRequest request) {
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(401, "用户不存在"));
        String nickname = request.nickname() == null ? "" : request.nickname().trim();
        if (nickname.isBlank()) {
            throw new BusinessException(400, "昵称不能为空");
        }
        user.setNickname(nickname);
        user.setAvatar(normalizeAvatar(request.avatar()));  // 校验并规范化头像
        return toResponse(userRepository.save(user));
    }

    /**
     * 修改密码 — 需要验证当前密码，修改后旧 Token 自动失效。
     *
     * @param userId  用户 ID
     * @param request 包含当前密码、新密码和确认密码的请求
     * @throws BusinessException 当前密码错误、两次新密码不一致、新旧密码相同时
     */
    @Transactional
    public void updatePassword(long userId, UpdatePasswordRequest request) {
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(401, "用户不存在"));
        // 验证当前密码
        if (!passwordHasher.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessException(400, "当前密码不正确");
        }
        // 检查两次新密码是否一致
        if (!request.newPassword().equals(request.confirmPassword())) {
            throw new BusinessException(400, "两次输入的新密码不一致");
        }
        // 新密码不能与当前密码相同
        if (passwordHasher.matches(request.newPassword(), user.getPasswordHash())) {
            throw new BusinessException(400, "新密码不能与当前密码相同");
        }
        user.incrementTokenVersion();  // 递增 Token 版本号，使旧 Token 失效
        user.setPasswordHash(passwordHasher.hash(request.newPassword()));
        userRepository.save(user);
    }

    /**
     * 用户登出 — 通过递增 tokenVersion 使当前 Token 失效。
     * 前端同时需要清除本地存储的 Token。
     *
     * @param userId 用户 ID
     * @throws BusinessException 用户不存在时
     */
    @Transactional
    public void logout(long userId) {
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(401, "用户不存在"));
        user.incrementTokenVersion();  // 递增版本号，旧 Token 立即失效
        userRepository.save(user);
    }

    /**
     * 校验并规范化头像值。
     * 支持两种格式：
     * <ul>
     *   <li>预设头像：以 "preset:" 开头的标识符，如 "preset:cat"</li>
     *   <li>上传头像：以 "data:image/" 开头的 Base64 图片数据</li>
     * </ul>
     *
     * @param avatar 头像值
     * @return 规范化后的头像值
     * @throws BusinessException 格式不合法或图片过大时
     */
    private String normalizeAvatar(String avatar) {
        if (avatar == null || avatar.isBlank()) {
            return "";  // 空值表示不修改头像
        }
        String normalized = avatar.trim();
        // 预设头像标识
        if (normalized.startsWith("preset:")) {
            if (normalized.length() > 40) {
                throw new BusinessException(400, "默认头像标识过长");
            }
            return normalized;
        }
        // Base64 图片数据
        if (!normalized.startsWith("data:image/")) {
            throw new BusinessException(400, "头像仅支持图片上传或默认头像");
        }
        // 限制头像大小不超过 256KB
        if (normalized.length() > 262144) {
            throw new BusinessException(400, "头像图片过大，请选择更小的图片");
        }
        return normalized;
    }

    /**
     * 规范化邮箱 — 去除首尾空格并转为小写。
     *
     * @param email 原始邮箱
     * @return 规范化后的邮箱，如果为空则返回 null
     */
    private String normalizeEmail(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    /**
     * 规范化必填字段 — 去除首尾空格，如果为空则抛出异常。
     *
     * @param value   原始值
     * @param message 为空时的错误提示
     * @return 规范化后的非空值
     * @throws BusinessException 值为空时
     */
    private String normalizeRequired(String value, String message) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new BusinessException(400, message);
        }
        return normalized;
    }

    /**
     * 根据用户名或邮箱查找用户。
     * 如果登录标识包含 "@" 符号，则按邮箱查找；否则按用户名查找。
     *
     * @param loginId 用户名或邮箱
     * @return 用户实体的 Optional 包装
     */
    private Optional<UserEntity> findByUsernameOrEmail(String loginId) {
        if (loginId.contains("@")) {
            return userRepository.findByEmail(normalizeEmail(loginId));
        }
        return userRepository.findByUsername(loginId);
    }

    /**
     * 生成登录尝试的唯一标识键，用于频率限制和验证码触发。
     * 格式为 "IP地址:登录标识"（全部小写）。
     *
     * @param ipAddress 客户端 IP 地址
     * @param loginId   用户名或邮箱
     * @return 登录尝试的唯一标识键
     */
    private String loginAttemptKey(String ipAddress, String loginId) {
        String ip = ipAddress == null ? "" : ipAddress.trim();
        String normalizedLoginId = loginId == null ? "" : loginId.trim().toLowerCase(Locale.ROOT);
        return (ip.isBlank() ? "unknown" : ip) + ":" + normalizedLoginId;
    }

    /**
     * 创建邮箱登录专用的用户实体。
     * 邮箱登录自动注册时使用，密码设为随机不可用值（因为用户不使用密码登录）。
     *
     * @param email 用户邮箱
     * @return 新创建的用户实体（尚未保存到数据库）
     * @throws BusinessException 如果该邮箱地址已被用作用户名时
     */
    private UserEntity createEmailUser(String email) {
        if (userRepository.existsByUsername(email)) {
            throw new BusinessException(400, "用户名已存在");
        }
        UserEntity user = new UserEntity();
        user.setUsername(email);               // 用户名默认为邮箱地址
        user.setEmail(email);
        user.setPasswordHash(passwordHasher.hash(randomUnusablePassword()));  // 随机不可用密码
        user.setNickname(email);               // 昵称默认为邮箱地址
        user.setRole(UserRole.USER);           // 默认角色为普通用户
        user.setStatus(UserStatus.ACTIVE);     // 默认状态为正常
        return user;
    }

    /**
     * 获取默认昵称 — 如果用户未提供昵称，则使用用户名作为昵称。
     *
     * @param nickname 用户提供的昵称
     * @param username 用户名
     * @return 昵称字符串
     */
    private String defaultNickname(String nickname, String username) {
        String normalized = nickname == null ? "" : nickname.trim();
        return normalized.isBlank() ? username : normalized;
    }

    /**
     * 生成随机的不可用密码。
     * 用于邮箱登录自动注册的用户，因为他们不需要密码登录。
     * 格式为 "email-login-" + 32位随机UUID，确保无法被猜到。
     *
     * @return 随机密码字符串
     */
    private String randomUnusablePassword() {
        return "email-login-" + UUID.randomUUID().toString().replace("-", "").toLowerCase(Locale.ROOT);
    }

    /**
     * 将用户实体转换为 API 响应对象。
     * 不包含密码等敏感信息，额外计算了管理权限、可见路由和权限列表。
     *
     * @param user 用户实体
     * @return 用户信息响应对象
     */
    private AuthUserResponse toResponse(UserEntity user) {
        return new AuthUserResponse(
            user.getId(),               // 用户 ID
            user.getUsername(),         // 用户名
            user.getNickname(),         // 昵称
            user.getAvatar(),           // 头像
            user.getRole().name(),      // 角色（USER / ADMIN）
            user.getStatus().name(),    // 状态（ACTIVE / DISABLED）
            user.getCreatedAt() == null ? null : user.getCreatedAt().format(DATETIME_FORMATTER), // 创建时间
            AuthAccessPolicy.canAccessAdminConsole(user.getRole()), // 是否能访问管理后台
            AuthAccessPolicy.visibleRoutes(user.getRole()),         // 可见的前端路由列表
            AuthAccessPolicy.permissions(user.getRole())            // 权限列表
        );
    }
}
