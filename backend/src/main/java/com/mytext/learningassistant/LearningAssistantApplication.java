package com.mytext.learningassistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 整个 Spring Boot 应用的启动入口。
 * {@code @SpringBootApplication} 告诉 Spring：从这里开始扫描所有的 Bean、配置和控制器。
 * 启动方式：运行 main() 方法，或执行 mvnw spring-boot:run。
 */
@SpringBootApplication
public class LearningAssistantApplication {

    /**
     * Java 程序的入口方法。
     * {@code SpringApplication.run()} 启动内嵌 Tomcat 服务器，加载所有配置并开始监听端口。
     *
     * @param args 命令行参数（本项目未使用）
     */
    public static void main(String[] args) {
        SpringApplication.run(LearningAssistantApplication.class, args);
    }
}
