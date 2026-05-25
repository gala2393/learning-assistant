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

@RestController
@RequestMapping("/api/favorites")
public class FavoriteController {

    private final RagService ragService;

    public FavoriteController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping
    public ApiResponse<FavoriteItemResponse> addFavorite(
        @RequestAttribute("currentUserId") long currentUserId,
        @Valid @RequestBody FavoriteRequest request
    ) {
        return ApiResponse.ok(ragService.addFavorite(currentUserId, request));
    }

    @GetMapping
    public ApiResponse<List<FavoriteItemResponse>> list(@RequestAttribute("currentUserId") long currentUserId) {
        return ApiResponse.ok(ragService.favorites(currentUserId));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        ragService.deleteFavorite(currentUserId, id);
        return ApiResponse.ok(null);
    }
}
