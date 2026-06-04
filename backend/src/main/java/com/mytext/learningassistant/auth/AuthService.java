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
 * 认证业务逻辑 — 处理注册、登录、密码重置、个人资料修改等核心认证操作。
 * <p>
 * 所有数据库写操作都加了 {@code @Transactional} 注解，确保数据一致性（要么全成功，要么全回滚）。
 * 只读查询方法标记了 {@code readOnly = true}，让 JPA 可以做性能优化（跳过脏检查）。
 */
@Service
public class AuthService {

    /** 日期格式化器：yyyy-MM-dd HH:mm:ss，用于响应中的时间字段 */
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 账号被禁用时的统一提示信息 */
    private static final String ACCOUNT_DISABLED_MESSAGE = "该账户已禁用，请联系管理员解除封禁。";

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final TokenService tokenService;
    private final EmailCodeService emailCodeService;

    public AuthService(
        UserRepository userRepository,
        PasswordHasher passwordHasher,
        TokenService tokenService,
        EmailCodeService emailCodeService
    ) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.tokenService = tokenService;
        this.emailCodeService = emailCodeService;
    }

    /**
     * 注册新用户。
     * <ol>
     *   <li>校验用户名不能为空</li>
     *   <li>校验两次密码一致</li>
     *   <li>校验邮箱验证码正确（如果提供了邮箱）</li>
     *   <li>检查用户名和邮箱是否已被占用</li>
     *   <li>加密密码，保存用户</li>
     *   <li>返回用户信息（自动登录由前端发起）</li>
     * </ol>
     */
    @Transactional
    public AuthUserResponse register(RegisterRequest request) {
        String username = normalizeRequired(request.username(), "请输入用户名");
        String email = normalizeEmail(request.email());

        // 校验两次密码一致
        if (request.confirmPassword() != null && !request.password().equals(request.confirmPassword())) {
            throw new BusinessException(400, "两次输入的密码不一致");
        }

        // 如果提供了邮箱，验证邮箱验证码
        if (email != null && !emailCodeService.verify(email, request.code())) {
            throw new BusinessException(400, "验证码错误或已过期");
        }

        // 检查是否已被占用
        if (userRepository.existsByUsername(username)) {
            throw new BusinessException(400, "用户名已存在");
        }
        if (email != null && userRepository.existsByEmail(email)) {
            throw new BusinessException(400, "邮箱已注册，请直接登录");
        }

        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordHasher.hash(request.password()));  // 加密密码
        user.setNickname(defaultNickname(request.nickname(), username));
        user.setRole(UserRole.USER);        // 新注册默认是普通用户
        user.setStatus(UserStatus.ACTIVE);  // 默认正常状态

        return toResponse(userRepository.save(user));
    }

    /** 发送邮箱验证码（委托给 EmailCodeService） */
    public void sendEmailCode(EmailCodeRequest request, String ipAddress) {
        emailCodeService.sendCode(request.email(), request.provider(), ipAddress);
    }

    /**
     * 邮箱验证码登录（如果邮箱未注册，自动创建账号）。
     * 这是为了方便用户快速登录 — 不需要先注册再登录。
     */
    @Transactional
    public LoginResponse emailLogin(EmailLoginRequest request) {
        String email = normalizeEmail(request.email());
        if (!emailCodeService.verify(email, request.code())) {
            throw new BusinessException(400, "验证码错误或已过期");
        }
        // 邮箱不存在时自动创建用户（密码为随机不可用值，只能通过验证码登录）
        UserEntity user = userRepository.findByEmail(email)
            .orElseGet(() -> userRepository.save(createEmailUser(email)));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(400, ACCOUNT_DISABLED_MESSAGE);
        }
        return new LoginResponse(tokenService.createToken(user), toResponse(user));
    }

    /**
     * 通过邮箱验证码重置密码。
     * 需要提供：邮箱、验证码、新密码、确认密码。
     */
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        String email = normalizeEmail(request.email());
        if (!request.newPassword().equals(request.confirmPassword())) {
            throw new BusinessException(400, "两次输入的密码不一致");
        }
        UserEntity user = userRepository.findByEmail(email)
            .orElseThrow(() -> new BusinessException(400, "该邮箱未注册，请先注册"));
        if (!emailCodeService.verify(email, request.code())) {
            throw new BusinessException(400, "验证码错误或已过期");
        }
        user.setPasswordHash(passwordHasher.hash(request.newPassword()));
        userRepository.save(user);
    }

    /**
     * 用户名/邮箱 + 密码登录。
     * 支持用户名和邮箱两种登录方式 — 如果 loginId 包含 @ 则按邮箱查询，否则按用户名查询。
     * 出于安全考虑，不管什么原因登录失败都返回相同的错误信息（防止枚举攻击）。
     */
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        String loginId = normalizeRequired(request.username(), "请输入用户名或邮箱");
        // 如果输入包含 @，按邮箱查询；否则按用户名查询
        UserEntity user = findByUsernameOrEmail(loginId)
            .orElseThrow(() -> new BusinessException(400, "用户名、邮箱或密码错误"));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(400, ACCOUNT_DISABLED_MESSAGE);
        }
        if (!passwordHasher.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(400, "用户名、邮箱或密码错误");
        }
        // 登录成功 → 生成 Token 返回
        return new LoginResponse(tokenService.createToken(user), toResponse(user));
    }

    /** 检查用户名是否可用（注册时实时校验） */
    @Transactional(readOnly = true)
    public UsernameAvailabilityResponse checkUsername(String username) {
        String normalized = username == null ? "" : username.trim();
        if (normalized.isBlank()) {
            return new UsernameAvailabilityResponse(normalized, false, "请输入用户名");
        }
        boolean available = !userRepository.existsByUsername(normalized);
        return new UsernameAvailabilityResponse(
            normalized, available,
            available ? "用户名可用" : "用户名已存在，请重新取名"
        );
    }

    /** 获取当前登录用户信息 */
    @Transactional(readOnly = true)
    public AuthUserResponse me(long userId) {
        return toResponse(userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(401, "用户不存在")));
    }

    /** 修改个人资料（昵称、头像） */
    @Transactional
    public AuthUserResponse updateMe(long userId, UpdateProfileRequest request) {
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(401, "用户不存在"));
        String nickname = request.nickname() == null ? "" : request.nickname().trim();
        if (nickname.isBlank()) {
            throw new BusinessException(400, "昵称不能为空");
        }
        user.setNickname(nickname);
        user.setAvatar(normalizeAvatar(request.avatar()));  // 校验头像格式
        return toResponse(userRepository.save(user));
    }

    /**
     * 修改密码。
     * 需要：当前密码正确 + 新密码两次一致 + 新密码与当前密码不同。
     */
    @Transactional
    public void updatePassword(long userId, UpdatePasswordRequest request) {
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(401, "用户不存在"));
        if (!passwordHasher.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessException(400, "当前密码不正确");
        }
        if (!request.newPassword().equals(request.confirmPassword())) {
            throw new BusinessException(400, "两次输入的新密码不一致");
        }
        if (passwordHasher.matches(request.newPassword(), user.getPasswordHash())) {
            throw new BusinessException(400, "新密码不能与当前密码相同");
        }
        user.setPasswordHash(passwordHasher.hash(request.newPassword()));
        userRepository.save(user);
    }

    /**
     * 校验头像格式 — 支持两种：
     * 1. preset:xxx — 系统预设渐变色头像（如 preset:gradient-1）
     * 2. data:image/xxx;base64,... — 用户上传的 Base64 图片
     */
    private String normalizeAvatar(String avatar) {
        if (avatar == null || avatar.isBlank()) {
            return "";
        }
        String normalized = avatar.trim();
        if (normalized.startsWith("preset:")) {
            if (normalized.length() > 40) {
                throw new BusinessException(400, "默认头像标识过长");
            }
            return normalized;
        }
        if (!normalized.startsWith("data:image/")) {
            throw new BusinessException(400, "头像仅支持图片上传或默认头像");
        }
        if (normalized.length() > 262144) {  // 约 256KB
            throw new BusinessException(400, "头像图片过大，请选择更小的图片");
        }
        return normalized;
    }

    /** 标准化邮箱地址 — 去空格、转小写。返回 null 表示未提供邮箱 */
    private String normalizeEmail(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    /** 标准化必填字段 — 空白时抛出异常 */
    private String normalizeRequired(String value, String message) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new BusinessException(400, message);
        }
        return normalized;
    }

    /** 根据登录标识查找用户 — 包含 @ 则按邮箱查，否则按用户名查 */
    private Optional<UserEntity> findByUsernameOrEmail(String loginId) {
        if (loginId.contains("@")) {
            return userRepository.findByEmail(normalizeEmail(loginId));
        }
        return userRepository.findByUsername(loginId);
    }

    /**
     * 为邮箱验证码登录创建新用户。
     * 密码设置为随机不可用值（用户只能通过验证码登录，无法用密码登录），
     * 除非后续设置了密码。
     */
    private UserEntity createEmailUser(String email) {
        if (userRepository.existsByUsername(email)) {
            throw new BusinessException(400, "用户名已存在");
        }
        UserEntity user = new UserEntity();
        user.setUsername(email);
        user.setEmail(email);
        user.setPasswordHash(passwordHasher.hash(randomUnusablePassword()));  // 随机密码
        user.setNickname(email);
        user.setRole(UserRole.USER);
        user.setStatus(UserStatus.ACTIVE);
        return user;
    }

    /** 如果用户没填昵称，就用用户名作为默认昵称 */
    private String defaultNickname(String nickname, String username) {
        String normalized = nickname == null ? "" : nickname.trim();
        return normalized.isBlank() ? username : normalized;
    }

    /** 生成一个不可用于密码登录的随机密码 */
    private String randomUnusablePassword() {
        return "email-login-" + UUID.randomUUID().toString().replace("-", "").toLowerCase(Locale.ROOT);
    }

    /** 把 UserEntity 转换为 AuthUserResponse（含权限信息） */
    private AuthUserResponse toResponse(UserEntity user) {
        return new AuthUserResponse(
            user.getId(),
            user.getUsername(),
            user.getNickname(),
            user.getAvatar(),
            user.getRole().name(),
            user.getStatus().name(),
            user.getCreatedAt() == null ? null : user.getCreatedAt().format(DATETIME_FORMATTER),
            AuthAccessPolicy.canAccessAdminConsole(user.getRole()),
            AuthAccessPolicy.visibleRoutes(user.getRole()),
            AuthAccessPolicy.permissions(user.getRole())
        );
    }
}
