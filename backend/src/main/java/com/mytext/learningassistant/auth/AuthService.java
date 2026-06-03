package com.mytext.learningassistant.auth;

import java.time.format.DateTimeFormatter;

import com.mytext.learningassistant.common.BusinessException;
import com.mytext.learningassistant.user.UserEntity;
import com.mytext.learningassistant.user.UserRepository;
import com.mytext.learningassistant.user.UserRole;
import com.mytext.learningassistant.user.UserStatus;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final TokenService tokenService;
    private final EmailCodeService emailCodeService;
    private static final String ACCOUNT_DISABLED_MESSAGE = "该账户已禁用，请联系管理员解除封禁。";

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

    @Transactional
    public AuthUserResponse register(RegisterRequest request) {
        String username = request.username() == null ? "" : request.username().trim();
        if (username.isBlank()) {
            throw new BusinessException(400, "用户名不能为空");
        }
        if (userRepository.existsByUsername(username)) {
            throw new BusinessException(400, "用户名已存在");
        }
        if (request.confirmPassword() != null && !String.valueOf(request.password()).equals(request.confirmPassword())) {
            throw new BusinessException(400, "两次输入的密码不一致");
        }
        String email = normalizeEmail(request.email());
        if (email != null && userRepository.existsByEmail(email)) {
            throw new BusinessException(400, "邮箱已注册");
        }

        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordHasher.hash(request.password()));
        user.setNickname(defaultNickname(request.nickname(), username));
        user.setRole(UserRole.USER);
        user.setStatus(UserStatus.ACTIVE);

        return toResponse(userRepository.save(user));
    }

    public void sendEmailCode(EmailCodeRequest request, String ipAddress) {
        emailCodeService.sendCode(request.email(), request.provider(), ipAddress);
    }

    @Transactional
    public LoginResponse emailLogin(EmailLoginRequest request) {
        if (!emailCodeService.verify(request.email(), request.code())) {
            throw new BusinessException(400, "验证码错误或已过期");
        }
        String email = normalizeEmail(request.email());
        UserEntity user = userRepository.findByEmail(email)
            .orElseGet(() -> createEmailLoginUser(email));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(400, ACCOUNT_DISABLED_MESSAGE);
        }
        return new LoginResponse(tokenService.createToken(user), toResponse(user));
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        String email = normalizeEmail(request.email());
        UserEntity user = userRepository.findByEmail(email)
            .orElseThrow(() -> new BusinessException(400, "该邮箱未注册，请先注册"));
        if (!emailCodeService.verify(request.email(), request.code())) {
            throw new BusinessException(400, "验证码错误或已过期");
        }
        if (!request.newPassword().equals(request.confirmPassword())) {
            throw new BusinessException(400, "两次输入的密码不一致");
        }
        user.setPasswordHash(passwordHasher.hash(request.newPassword()));
        userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        String identifier = request.username().trim();
        UserEntity user = findByUsernameOrEmail(identifier)
            .orElseThrow(() -> new BusinessException(400, "用户名或密码错误"));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(400, ACCOUNT_DISABLED_MESSAGE);
        }
        if (!passwordHasher.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(400, "用户名或密码错误");
        }
        return new LoginResponse(tokenService.createToken(user), toResponse(user));
    }

    private java.util.Optional<UserEntity> findByUsernameOrEmail(String identifier) {
        if (identifier.contains("@")) {
            return userRepository.findByEmail(normalizeEmail(identifier));
        }
        return userRepository.findByUsername(identifier);
    }

    private UserEntity createEmailLoginUser(String email) {
        if (userRepository.existsByUsername(email)) {
            throw new BusinessException(400, "用户名已存在");
        }
        UserEntity user = new UserEntity();
        user.setUsername(email);
        user.setEmail(email);
        user.setPasswordHash(passwordHasher.hash(java.util.UUID.randomUUID().toString()));
        user.setNickname(email);
        user.setRole(UserRole.USER);
        user.setStatus(UserStatus.ACTIVE);
        return userRepository.save(user);
    }

    private String defaultNickname(String nickname, String username) {
        String normalized = nickname == null ? "" : nickname.trim();
        return normalized.isBlank() ? username : normalized;
    }

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

    @Transactional(readOnly = true)
    public AuthUserResponse me(long userId) {
        return toResponse(userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(401, "用户不存在")));
    }

    @Transactional
    public AuthUserResponse updateMe(long userId, UpdateProfileRequest request) {
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(401, "用户不存在"));
        String nickname = request.nickname() == null ? "" : request.nickname().trim();
        if (nickname.isBlank()) {
            throw new BusinessException(400, "昵称不能为空");
        }
        user.setNickname(nickname);
        user.setAvatar(normalizeAvatar(request.avatar()));
        return toResponse(userRepository.save(user));
    }

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
        if (normalized.length() > 262144) {
            throw new BusinessException(400, "头像图片过大，请选择更小的图片");
        }
        return normalized;
    }

    private String normalizeEmail(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

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
