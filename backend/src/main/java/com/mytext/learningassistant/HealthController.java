package com.mytext.learningassistant;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查控制器，用于确认后端是否正常运行。
 * 这两个接口不需要登录（已在 AuthInterceptor 的 PUBLIC_PATHS 中配置为公开路径）。
 * Docker/Railway 等部署平台会定期访问 /api/health 来判断服务是否存活。
 */
@RestController
class HealthController {

    /**
     * 根路径接口，返回基本服务信息。
     * 访问 http://localhost:8080/ 即可看到。
     *
     * @return 包含 status、service、message 的 Map
     */
    @GetMapping("/")
    Map<String, String> root() {
        return Map.of(
            "status", "ok",
            "service", "learning-assistant",
            "message", "backend is running"
        );
    }

    /**
     * 健康检查接口，被 Docker Compose / Railway 等平台用于判断服务是否存活。
     * 访问 http://localhost:8080/api/health 即可看到。
     *
     * @return 包含 status 和 service 的 Map
     */
    @GetMapping("/api/health")
    Map<String, String> health() {
        return Map.of(
            "status", "ok",
            "service", "learning-assistant"
        );
    }
}
