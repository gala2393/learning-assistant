package com.mytext.learningassistant.admin;

import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.mytext.learningassistant.common.BusinessException;
import com.mytext.learningassistant.common.PageResponse;
import com.mytext.learningassistant.material.LearningMaterialEntity;
import com.mytext.learningassistant.material.LearningMaterialRepository;
import com.mytext.learningassistant.material.MaterialParseStatus;
import com.mytext.learningassistant.material.MaterialSummaryStatus;
import com.mytext.learningassistant.rag.RagQuestionRepository;
import com.mytext.learningassistant.rag.UserFavoriteRepository;
import com.mytext.learningassistant.user.UserEntity;
import com.mytext.learningassistant.user.UserRepository;
import com.mytext.learningassistant.user.UserRole;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminService {

    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UserRepository userRepository;
    private final LearningMaterialRepository learningMaterialRepository;
    private final RagQuestionRepository ragQuestionRepository;
    private final UserFavoriteRepository userFavoriteRepository;
    private final SystemLogRepository systemLogRepository;

    public AdminService(
        UserRepository userRepository,
        LearningMaterialRepository learningMaterialRepository,
        RagQuestionRepository ragQuestionRepository,
        UserFavoriteRepository userFavoriteRepository,
        SystemLogRepository systemLogRepository
    ) {
        this.userRepository = userRepository;
        this.learningMaterialRepository = learningMaterialRepository;
        this.ragQuestionRepository = ragQuestionRepository;
        this.userFavoriteRepository = userFavoriteRepository;
        this.systemLogRepository = systemLogRepository;
    }

    @Transactional(readOnly = true)
    public AdminStatsResponse stats(long currentUserId) {
        requireAdmin(currentUserId);
        return new AdminStatsResponse(
            userRepository.count(),
            learningMaterialRepository.count(),
            ragQuestionRepository.count(),
            userFavoriteRepository.count(),
            systemLogRepository.count()
        );
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminUserResponse> users(long currentUserId, String keyword, int page, int size) {
        requireAdmin(currentUserId);
        List<AdminUserResponse> users = userRepository.findAllByOrderByCreatedAtDesc()
            .stream()
            .filter(user -> matchesUser(user, keyword))
            .map(this::toUserResponse)
            .toList();
        return page(users, page, size);
    }

    @Transactional
    public AdminUserResponse updateUserRole(long currentUserId, long userId, String roleValue) {
        requireAdmin(currentUserId);
        UserRole role = parseRole(roleValue);
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(404, "用户不存在"));
        if (currentUserId == userId && role != UserRole.ADMIN) {
            throw new BusinessException(403, "cannot remove your own admin role");
        }
        if (user.getRole() == UserRole.ADMIN && role != UserRole.ADMIN
            && userRepository.countByRole(UserRole.ADMIN) <= 1) {
            throw new BusinessException(403, "at least one admin is required");
        }
        user.setRole(role);
        UserEntity saved = userRepository.save(user);
        recordLog(currentUserId, "UPDATE_USER_ROLE", "USER", userId, "role=" + role.name());
        return toUserResponse(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminMaterialResponse> materials(long currentUserId, String keyword, int page, int size) {
        requireAdmin(currentUserId);
        var materials = learningMaterialRepository.findAllByOrderByCreatedAtDesc();
        Map<Long, UserEntity> owners = loadUsersById(
            materials.stream().map(LearningMaterialEntity::getOwnerId).collect(Collectors.toSet())
        );
        List<AdminMaterialResponse> responses = materials.stream()
            .map(material -> toMaterialResponse(material, owners.get(material.getOwnerId())))
            .filter(material -> matchesMaterial(material, keyword))
            .toList();
        return page(responses, page, size);
    }

    @Transactional
    public AdminMaterialResponse updateMaterialStatus(
        long currentUserId,
        long materialId,
        String parseStatusValue,
        String summaryStatusValue
    ) {
        requireAdmin(currentUserId);
        if ((parseStatusValue == null || parseStatusValue.isBlank())
            && (summaryStatusValue == null || summaryStatusValue.isBlank())) {
            throw new BusinessException(400, "至少需要传入一个资料状态");
        }

        LearningMaterialEntity material = learningMaterialRepository.findById(materialId)
            .orElseThrow(() -> new BusinessException(404, "资料不存在"));
        if (parseStatusValue != null && !parseStatusValue.isBlank()) {
            material.setParseStatus(parseParseStatus(parseStatusValue));
        }
        if (summaryStatusValue != null && !summaryStatusValue.isBlank()) {
            material.setSummaryStatus(parseSummaryStatus(summaryStatusValue));
        }
        LearningMaterialEntity saved = learningMaterialRepository.save(material);
        recordLog(
            currentUserId,
            "UPDATE_MATERIAL_STATUS",
            "MATERIAL",
            materialId,
            "parseStatus=" + saved.getParseStatus().name() + ", summaryStatus=" + saved.getSummaryStatus().name()
        );
        UserEntity owner = userRepository.findById(saved.getOwnerId()).orElse(null);
        return toMaterialResponse(saved, owner);
    }

    @Transactional(readOnly = true)
    public PageResponse<SystemLogResponse> logs(long currentUserId, String keyword, int page, int size) {
        requireAdmin(currentUserId);
        var logs = systemLogRepository.findAllByOrderByCreatedAtDesc();
        Map<Long, UserEntity> actors = loadUsersById(
            logs.stream().map(SystemLogEntity::getActorUserId).collect(Collectors.toSet())
        );
        List<SystemLogResponse> responses = logs.stream()
            .map(log -> toLogResponse(log, actors.get(log.getActorUserId())))
            .filter(log -> matchesLog(log, keyword))
            .toList();
        return page(responses, page, size);
    }

    private UserEntity requireAdmin(long currentUserId) {
        UserEntity user = userRepository.findById(currentUserId)
            .orElseThrow(() -> new BusinessException(401, "用户不存在"));
        if (user.getRole() != UserRole.ADMIN) {
            throw new BusinessException(403, "需要管理员权限");
        }
        return user;
    }

    private Map<Long, UserEntity> loadUsersById(java.util.Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return userRepository.findAllById(ids)
            .stream()
            .collect(Collectors.toMap(UserEntity::getId, Function.identity()));
    }

    private boolean matchesUser(UserEntity user, String keyword) {
        String needle = normalizeKeyword(keyword);
        if (needle.isBlank()) {
            return true;
        }
        return contains(user.getUsername(), needle)
            || contains(user.getNickname(), needle)
            || contains(user.getRole().name(), needle)
            || contains(user.getStatus().name(), needle);
    }

    private boolean matchesMaterial(AdminMaterialResponse material, String keyword) {
        String needle = normalizeKeyword(keyword);
        if (needle.isBlank()) {
            return true;
        }
        return contains(material.title(), needle)
            || contains(material.ownerUsername(), needle)
            || contains(material.sourceType(), needle)
            || contains(material.parseStatus(), needle)
            || contains(material.summaryStatus(), needle)
            || contains(material.originalName(), needle)
            || contains(material.sourceUrl(), needle);
    }

    private boolean matchesLog(SystemLogResponse log, String keyword) {
        String needle = normalizeKeyword(keyword);
        if (needle.isBlank()) {
            return true;
        }
        return contains(log.actorUsername(), needle)
            || contains(log.action(), needle)
            || contains(log.targetType(), needle)
            || contains(log.detail(), needle)
            || contains(log.createdAt(), needle)
            || contains(log.actorUserId() == null ? "" : log.actorUserId().toString(), needle)
            || contains(log.targetId() == null ? "" : log.targetId().toString(), needle);
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private String normalizeKeyword(String keyword) {
        return keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
    }

    private <T> PageResponse<T> page(List<T> items, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(size, 1), 100);
        long offset = (long) safePage * safeSize;
        int fromIndex = offset >= items.size() ? items.size() : (int) offset;
        int toIndex = Math.min(fromIndex + safeSize, items.size());
        return new PageResponse<>(items.subList(fromIndex, toIndex), safePage, safeSize, items.size());
    }

    private UserRole parseRole(String roleValue) {
        try {
            return UserRole.valueOf(roleValue.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Exception exception) {
            throw new BusinessException(400, "不支持的用户角色");
        }
    }

    private MaterialParseStatus parseParseStatus(String value) {
        try {
            return MaterialParseStatus.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Exception exception) {
            throw new BusinessException(400, "不支持的资料解析状态");
        }
    }

    private MaterialSummaryStatus parseSummaryStatus(String value) {
        try {
            return MaterialSummaryStatus.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Exception exception) {
            throw new BusinessException(400, "不支持的资料总结状态");
        }
    }

    private void recordLog(long actorUserId, String action, String targetType, Long targetId, String detail) {
        SystemLogEntity log = new SystemLogEntity();
        log.setActorUserId(actorUserId);
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setDetail(detail);
        systemLogRepository.save(log);
    }

    private AdminUserResponse toUserResponse(UserEntity user) {
        return new AdminUserResponse(
            user.getId(),
            user.getUsername(),
            user.getNickname(),
            user.getRole().name(),
            user.getStatus().name(),
            format(user.getCreatedAt()),
            format(user.getUpdatedAt())
        );
    }

    private AdminMaterialResponse toMaterialResponse(LearningMaterialEntity material, UserEntity owner) {
        return new AdminMaterialResponse(
            material.getId(),
            material.getOwnerId(),
            owner == null ? "" : owner.getUsername(),
            material.getTitle(),
            material.getSourceType().name(),
            material.getOriginalName(),
            material.getSourceUrl(),
            material.getFileSize(),
            material.getParseStatus().name(),
            material.getSummaryStatus().name(),
            material.getChunkCount(),
            format(material.getCreatedAt()),
            format(material.getUpdatedAt())
        );
    }

    private SystemLogResponse toLogResponse(SystemLogEntity log, UserEntity actor) {
        return new SystemLogResponse(
            log.getId(),
            log.getActorUserId(),
            actor == null ? "" : actor.getUsername(),
            log.getAction(),
            log.getTargetType(),
            log.getTargetId(),
            log.getDetail(),
            format(log.getCreatedAt())
        );
    }

    private String format(java.time.LocalDateTime value) {
        return value == null ? null : value.format(DATETIME_FORMATTER);
    }
}
