package com.mytext.learningassistant.rag;

import java.util.List;

import com.mytext.learningassistant.common.ApiResponse;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 收藏夹控制器 —— 提供用户收藏问答记录的 REST API。
 *
 * <p>用户可以将有价值的问答记录收藏起来，方便以后回顾学习。本控制器提供以下功能：</p>
 * <ul>
 *   <li>添加收藏：将一条问答记录加入收藏夹</li>
 *   <li>查看收藏列表：获取当前用户的所有收藏</li>
 *   <li>删除收藏：从收藏夹中移除某条记录</li>
 * </ul>
 *
 * <p>所有接口均需要通过拦截器注入 {@code currentUserId}，确保用户只能操作自己的收藏。</p>
 */
@RestController
@RequestMapping("/api/favorites")
public class FavoriteController {

    /** RAG 服务，处理收藏相关的业务逻辑 */
    private final RagService ragService;

    /**
     * 构造函数，通过依赖注入获取 RagService。
     *
     * @param ragService RAG 业务服务
     */
    public FavoriteController(RagService ragService) {
        this.ragService = ragService;
    }

    /**
     * 添加收藏 —— 将指定问答记录加入当前用户的收藏夹。
     *
     * @param currentUserId 当前登录用户的 ID（由拦截器自动注入）
     * @param request       收藏请求，包含要收藏的问答 ID
     * @return 包含收藏详情的统一响应
     */
    @PostMapping
    public ApiResponse<FavoriteItemResponse> addFavorite(
        @RequestAttribute("currentUserId") long currentUserId,
        @Valid @RequestBody FavoriteRequest request
    ) {
        return ApiResponse.ok(ragService.addFavorite(currentUserId, request));
    }

    /**
     * 获取收藏列表 —— 返回当前用户的所有收藏记录。
     *
     * @param currentUserId 当前登录用户的 ID
     * @return 收藏记录列表
     */
    @GetMapping
    public ApiResponse<List<FavoriteItemResponse>> list(@RequestAttribute("currentUserId") long currentUserId) {
        return ApiResponse.ok(ragService.favorites(currentUserId));
    }

    /**
     * 删除收藏 —— 从收藏夹中移除指定记录。
     *
     * @param currentUserId 当前登录用户的 ID
     * @param id            收藏记录的 ID
     * @return 空数据的统一响应
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        ragService.deleteFavorite(currentUserId, id);
        return ApiResponse.ok(null);
    }
}
