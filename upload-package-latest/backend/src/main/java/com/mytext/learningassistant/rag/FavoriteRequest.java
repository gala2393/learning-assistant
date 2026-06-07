package com.mytext.learningassistant.rag;

import jakarta.validation.constraints.NotNull;

/**
 * 收藏请求记录，表示用户要收藏某条问答记录的请求。
 *
 * @param questionId 要收藏的问答 ID（必填）
 */
public record FavoriteRequest(
    @NotNull(message = "问题ID不能为空")
    Long questionId
) {
}
