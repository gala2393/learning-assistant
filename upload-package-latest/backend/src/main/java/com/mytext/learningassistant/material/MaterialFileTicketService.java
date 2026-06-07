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
 * <p>为需要在无 Cookie 环境下下载资料文件的场景（如 iframe、img 标签）提供一次性鉴权令牌。
 * 凭据存储在内存中（ConcurrentHashMap），带有时效控制和自动清理机制。
 *
 * <p>工作流程：
 * <ol>
 *   <li>前端调用 {@link #create} 获取一个带有 TTL 的随机 token 和拼好的下载 URL</li>
 *   <li>前端将 URL 设置到 iframe/img 的 src 中</li>
 *   <li>后端在收到文件下载请求时调用 {@link #validate} 验证该 token</li>
 *   <li>token 在有效期内可重复使用（支持浏览器 PDF 预览的多次 Range 请求）</li>
 *   <li>token 过期后自动从内存中清除，防止内存泄漏</li>
 * </ol>
 *
 * @see MaterialController#fileTicket 前端调用入口
 * @see MaterialController#file 使用 ticket 的文件下载接口
 */
@Service
public class MaterialFileTicketService {

    /**
     * 凭据有效期：30 分钟（毫秒）。
     * <p>
     * 设计说明：较长的有效期是为了支持浏览器 PDF 预览场景——用户打开 PDF 后可能停留较长时间，
     * 期间浏览器会多次发送 Range 请求加载不同页面。如果有效期太短，预览过程中 ticket 会失效。
     */
    private static final long TICKET_TTL_MILLIS = 30 * 60 * 1000L;

    /** 密码学安全随机数生成器，用于生成不可预测的 token，防止凭据被猜测 */
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 内存中的凭据存储。
     * <p>
     * key 为 token 字符串，value 为凭据详情（包含 ownerId、materialId、expiresAt）。
     * 使用 ConcurrentHashMap 保证线程安全，支持并发的凭据创建和验证操作。
     */
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    /**
     * 创建一个新的文件下载凭据。
     *
     * <p>生成流程：
     * <ol>
     *   <li>先清理过期凭据，防止内存泄漏</li>
     *   <li>生成 24 字节的密码学安全随机 token（URL 安全 Base64 编码，无填充字符）</li>
     *   <li>计算过期时间戳（当前时间 + 30 分钟）</li>
     *   <li>将凭据信息存入内存 Map</li>
     *   <li>拼装下载 URL 并返回</li>
     * </ol>
     *
     * @param ownerId    资料所有者的用户 ID
     * @param materialId 资料 ID
     * @return 包含 token、下载 URL 和过期时间的响应对象
     */
    public MaterialFileTicketResponse create(long ownerId, long materialId) {
        // 每次创建前先清理过期的凭据，防止内存泄漏
        cleanupExpired();

        // 生成 24 字节的随机 token，URL 安全 Base64 编码（无填充）
        // 24 字节 = 192 位的随机性，足以防止暴力猜测
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        // 计算过期时间：当前时间 + 30 分钟
        long expiresAt = Instant.now().toEpochMilli() + TICKET_TTL_MILLIS;

        // 存入凭据记录
        tickets.put(token, new Ticket(ownerId, materialId, expiresAt));

        // 拼装完整的下载 URL，前端可直接使用
        return new MaterialFileTicketResponse(
            token,
            "/api/materials/" + materialId + "/file?ticket=" + token,
            expiresAt
        );
    }

    /**
     * 验证一个文件下载凭据。
     *
     * <p>验证逻辑：
     * <ol>
     *   <li>token 不能为空或纯空白</li>
     *   <li>token 必须存在于内存 Map 中</li>
     *   <li>凭据中的 materialId 必须与请求的资料 ID 一致</li>
     *   <li>凭据必须在有效期内（未过期）</li>
     * </ol>
     *
     * <p>注意：凭据在有效期内可重复使用（不会被删除），这与"一次性"语义略有不同。
     * 设计如此是因为浏览器 PDF 预览会发送多次 Range 请求，如果每次验证后删除凭据，
     * 后续请求会失败。
     *
     * @param token      待验证的凭据 token
     * @param materialId 请求下载的资料 ID（用于校验凭据是否匹配）
     * @return 凭据对应的 owner 用户 ID；验证失败返回 null
     */
    public Long validate(String token, long materialId) {
        // 空 token 直接返回 null
        if (token == null || token.isBlank()) {
            return null;
        }

        // 从内存中获取凭据记录
        Ticket ticket = tickets.get(token);

        // 验证：记录不存在、资料 ID 不匹配、或已过期 => 清除并返回 null
        if (ticket == null
            || ticket.materialId() != materialId
            || ticket.expiresAt() < Instant.now().toEpochMilli()) {
            tickets.remove(token);
            return null;
        }

        // 验证通过，返回所有者 ID
        return ticket.ownerId();
    }

    /**
     * 清理所有已过期的凭据，释放内存。
     *
     * <p>在每次创建新凭据时自动调用，采用"惰性清理"策略——
     * 只在有新请求时才清理，避免引入定时任务的复杂性。
     */
    private void cleanupExpired() {
        long now = Instant.now().toEpochMilli();
        // 遍历所有凭据，删除已过期的条目
        tickets.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
    }

    /**
     * 内部凭据记录，使用 Java record 定义不可变数据结构。
     *
     * @param ownerId    资料所有者 ID（凭据持有者可访问的用户）
     * @param materialId 资料 ID（凭据绑定的具体资料）
     * @param expiresAt  过期时间戳（毫秒，基于 epoch）
     */
    private record Ticket(long ownerId, long materialId, long expiresAt) {
    }
}
