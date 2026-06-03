package com.mytext.learningassistant.admin;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.mytext.learningassistant.common.BusinessException;
import com.mytext.learningassistant.common.PageResponse;
import com.mytext.learningassistant.material.LearningMaterialEntity;
import com.mytext.learningassistant.material.LearningMaterialRepository;
import com.mytext.learningassistant.material.MaterialParseStatus;
import com.mytext.learningassistant.material.MaterialSummaryStatus;
import com.mytext.learningassistant.rag.RagQuestionEntity;
import com.mytext.learningassistant.rag.RagQuestionRepository;
import com.mytext.learningassistant.rag.UserFavoriteRepository;
import com.mytext.learningassistant.user.UserEntity;
import com.mytext.learningassistant.user.UserRepository;
import com.mytext.learningassistant.user.UserRole;
import com.mytext.learningassistant.user.UserStatus;

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
    private final UsageRecordRepository usageRecordRepository;

    public AdminService(
        UserRepository userRepository,
        LearningMaterialRepository learningMaterialRepository,
        RagQuestionRepository ragQuestionRepository,
        UserFavoriteRepository userFavoriteRepository,
        SystemLogRepository systemLogRepository,
        UsageRecordRepository usageRecordRepository
    ) {
        this.userRepository = userRepository;
        this.learningMaterialRepository = learningMaterialRepository;
        this.ragQuestionRepository = ragQuestionRepository;
        this.userFavoriteRepository = userFavoriteRepository;
        this.systemLogRepository = systemLogRepository;
        this.usageRecordRepository = usageRecordRepository;
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

    @Transactional
    public AdminUserResponse updateUserStatus(long currentUserId, long userId, String statusValue) {
        requireAdmin(currentUserId);
        UserStatus status = parseStatus(statusValue);
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(404, "用户不存在"));
        if (currentUserId == userId && status == UserStatus.DISABLED) {
            throw new BusinessException(403, "不能禁用当前登录的管理员账号");
        }
        user.setStatus(status);
        UserEntity saved = userRepository.save(user);
        recordLog(currentUserId, "UPDATE_USER_STATUS", "USER", userId, "status=" + status.name());
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
            .filter(log -> !isUsageAction(log.getAction()))
            .map(log -> toLogResponse(log, actors.get(log.getActorUserId())))
            .filter(log -> matchesLog(log, keyword))
            .toList();
        return page(responses, page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<UsageRecordResponse> usageRecords(long currentUserId, String keyword, int page, int size) {
        requireAdmin(currentUserId);
        var records = usageRecordRepository.findAllByOrderByCreatedAtDesc();
        Map<Long, UserEntity> actors = loadUsersById(
            records.stream().map(UsageRecordEntity::getUserId).collect(Collectors.toSet())
        );
        List<UsageRecordResponse> responses = records.stream()
            .map(record -> toUsageRecordResponse(record, actors.get(record.getUserId())))
            .filter(record -> matchesUsageRecord(record, keyword))
            .toList();
        List<SystemLogEntity> usageLogs = systemLogRepository.findAllByOrderByCreatedAtDesc().stream()
            .filter(log -> isUsageAction(log.getAction()))
            .toList();
        Map<Long, UserEntity> usageLogActors = loadUsersById(
            usageLogs.stream().map(SystemLogEntity::getActorUserId).collect(Collectors.toSet())
        );
        List<UsageRecordResponse> combined = new ArrayList<>(responses);
        usageLogs.stream()
            .map(log -> toUsageRecordResponse(log, usageLogActors.get(log.getActorUserId())))
            .filter(record -> matchesUsageRecord(record, keyword))
            .forEach(combined::add);
        appendRagQuestionUsageRecords(combined, keyword);
        combined.sort(Comparator.comparing(UsageRecordResponse::createdAt, Comparator.nullsLast(String::compareTo)).reversed());
        return page(combined, page, size);
    }

    private void appendRagQuestionUsageRecords(List<UsageRecordResponse> combined, String keyword) {
        Set<Long> recordedQuestionIds = combined.stream()
            .filter(record -> "RAG_QUESTION".equals(record.targetType()))
            .map(UsageRecordResponse::targetId)
            .filter(id -> id != null && id > 0)
            .collect(Collectors.toCollection(HashSet::new));
        List<RagQuestionEntity> questions = ragQuestionRepository.findAll();
        Map<Long, UserEntity> users = loadUsersById(
            questions.stream().map(RagQuestionEntity::getUserId).collect(Collectors.toSet())
        );
        questions.stream()
            .filter(question -> question.getId() != null && !recordedQuestionIds.contains(question.getId()))
            .map(question -> toUsageRecordResponse(question, users.get(question.getUserId())))
            .filter(record -> matchesUsageRecord(record, keyword))
            .forEach(combined::add);
    }

    private boolean matchesUsageRecord(UsageRecordResponse record, String keyword) {
        String needle = normalizeKeyword(keyword);
        if (needle.isBlank()) {
            return true;
        }
        return contains(record.username(), needle)
            || contains(record.action(), needle)
            || contains(record.targetType(), needle)
            || contains(record.detail(), needle)
            || contains(record.modelName(), needle)
            || contains(record.createdAt(), needle)
            || contains(record.userId() == null ? "" : record.userId().toString(), needle);
    }

    private boolean isUsageAction(String action) {
        return "RAG_CHAT".equals(action)
            || "RAG_CHAT_STREAM".equals(action)
            || "UPLOAD_MATERIAL".equals(action)
            || "CREATE_UPLOAD_SESSION".equals(action);
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

    private UserStatus parseStatus(String statusValue) {
        try {
            return UserStatus.valueOf(statusValue.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Exception exception) {
            throw new BusinessException(400, "不支持的用户状态");
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
            material.getParseProgressPercent(),
            material.getParseStage(),
            material.getParseMessage(),
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

    private UsageRecordResponse toUsageRecordResponse(UsageRecordEntity record, UserEntity user) {
        return new UsageRecordResponse(
            record.getId(),
            record.getUserId(),
            user == null ? "" : user.getUsername(),
            record.getAction(),
            record.getTargetType(),
            record.getTargetId(),
            record.getModelName(),
            record.getPromptTokens(),
            record.getCompletionTokens(),
            record.getTotalTokens(),
            record.getDetail(),
            format(record.getCreatedAt())
        );
    }

    private UsageRecordResponse toUsageRecordResponse(SystemLogEntity log, UserEntity user) {
        return new UsageRecordResponse(
            log.getId(),
            log.getActorUserId(),
            user == null ? "" : user.getUsername(),
            log.getAction(),
            log.getTargetType(),
            log.getTargetId(),
            detailValue(log.getDetail(), "model"),
            detailIntValue(log.getDetail(), "promptTokens"),
            detailIntValue(log.getDetail(), "completionTokens"),
            detailIntValue(log.getDetail(), "totalTokens"),
            log.getDetail(),
            format(log.getCreatedAt())
        );
    }

    private UsageRecordResponse toUsageRecordResponse(RagQuestionEntity question, UserEntity user) {
        String detail = "model=" + valueOrDash(question.getModelName())
            + ", customModel=" + question.isCustomModel()
            + ", promptTokens=" + intOrZero(question.getPromptTokens())
            + ", completionTokens=" + intOrZero(question.getCompletionTokens())
            + ", totalTokens=" + intOrZero(question.getTotalTokens())
            + ", question=" + excerpt(question.getQuestionText());
        return new UsageRecordResponse(
            question.getId(),
            question.getUserId(),
            user == null ? "" : user.getUsername(),
            "RAG_CHAT_STREAM",
            "RAG_QUESTION",
            question.getId(),
            question.getModelName(),
            question.getPromptTokens(),
            question.getCompletionTokens(),
            question.getTotalTokens(),
            detail,
            format(question.getCreatedAt())
        );
    }

    private String valueOrDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private int intOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private String excerpt(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replace("\r", " ").replace("\n", " ").trim();
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80) + "...";
    }

    private String detailValue(String detail, String key) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        String prefix = key + "=";
        for (String part : detail.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(prefix)) {
                String value = trimmed.substring(prefix.length()).trim();
                return value.isBlank() ? null : value;
            }
        }
        return null;
    }

    private Integer detailIntValue(String detail, String key) {
        String value = detailValue(detail, key);
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String format(java.time.LocalDateTime value) {
        return value == null ? null : value.format(DATETIME_FORMATTER);
    }
}
