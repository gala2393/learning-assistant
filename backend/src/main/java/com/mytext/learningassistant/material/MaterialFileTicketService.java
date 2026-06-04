package com.mytext.learningassistant.material;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

/**
 * 文件下载凭据（Ticket）服务。
 *
 * 为需要在无 Cookie 环境下下载资料文件的场景（如 iframe、img 标签）提供一次性鉴权令牌。
 *
 * <p>工作流程：
 * <ol>
 *   <li>前端调用 {@link #create} 获取一个带有 TTL 的随机 token 和拼好的下载 URL</li>
 *   <li>前端将 URL 设置到 iframe/img 的 src 中</li>
 *   <li>后端在收到文件下载请求时调用 {@link #consume} 验证并消费该 token</li>
 *   <li>token 一次性使用后立即失效，2 分钟后过期自动清理</li>
 * </ol>
 */
@Service
public class MaterialFileTicketService {

    /** 凭据有效期：2 分钟（毫秒） */
    private static final long TICKET_TTL_MILLIS = 2 * 60 * 1000L;

    /** 密码学安全随机数生成器，用于生成不可预测的 token */
    private final SecureRandom secureRandom = new SecureRandom();

    /** 内存中的凭据存储，key 为 token 字符串，value 为凭据详情 */
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    /**
     * 创建一个新的文件下载凭据。
     *
     * @param ownerId    资料所有者的用户 ID
     * @param materialId 资料 ID
     * @return 包含 token、下载 URL 和过期时间的响应对象
     */
    public MaterialFileTicketResponse create(long ownerId, long materialId) {
        // 每次创建前先清理过期的凭据，防止内存泄漏
        cleanupExpired();

        // 生成 24 字节的随机 token，URL 安全 Base64 编码（无填充）
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        long expiresAt = Instant.now().toEpochMilli() + TICKET_TTL_MILLIS;
        tickets.put(token, new Ticket(ownerId, materialId, expiresAt));

        return new MaterialFileTicketResponse(
            token,
            "/api/materials/" + materialId + "/file?ticket=" + token,
            expiresAt
        );
    }

    /**
     * 验证并消费一个文件下载凭据。
     * 凭据使用后立即删除（一次性消费），如果凭据无效、已过期或资料 ID 不匹配则返回 null。
     *
     * @param token      待验证的凭据 token
     * @param materialId 请求下载的资料 ID
     * @return 凭据对应的 owner 用户 ID；验证失败返回 null
     */
    public Long consume(String token, long materialId) {
        if (token == null || token.isBlank()) {
            return null;
        }
        // remove 保证一次性消费
        Ticket ticket = tickets.remove(token);
        if (ticket == null
            || ticket.materialId() != materialId
            || ticket.expiresAt() < Instant.now().toEpochMilli()) {
            return null;
        }
        return ticket.ownerId();
    }

    /**
     * 清理所有已过期的凭据，释放内存。
     */
    private void cleanupExpired() {
        long now = Instant.now().toEpochMilli();
        tickets.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
    }

    /**
     * 内部凭据记录。
     *
     * @param ownerId    资料所有者 ID
     * @param materialId 资料 ID
     * @param expiresAt  过期时间戳（毫秒）
     */
    private record Ticket(long ownerId, long materialId, long expiresAt) {
    }
}
