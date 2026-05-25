package com.mytext.learningassistant.auth;

import com.mytext.learningassistant.common.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ApiResponse<AuthUserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(authService.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @GetMapping("/check-username")
    public ApiResponse<UsernameAvailabilityResponse> checkUsername(@RequestParam("username") String username) {
        return ApiResponse.ok(authService.checkUsername(username));
    }

    @GetMapping("/me")
    public ApiResponse<AuthUserResponse> me(@RequestAttribute("currentUserId") long currentUserId) {
        return ApiResponse.ok(authService.me(currentUserId));
    }

    @PutMapping("/me")
    public ApiResponse<AuthUserResponse> updateMe(
        @RequestAttribute("currentUserId") long currentUserId,
        @Valid @RequestBody UpdateProfileRequest request
    ) {
        return ApiResponse.ok(authService.updateMe(currentUserId, request));
    }

    @PutMapping("/password")
    public ApiResponse<Void> updatePassword(
        @RequestAttribute("currentUserId") long currentUserId,
        @Valid @RequestBody UpdatePasswordRequest request
    ) {
        authService.updatePassword(currentUserId, request);
        return ApiResponse.ok(null);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        return ApiResponse.ok(null);
    }
}
