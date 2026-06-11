package com.mytext.learningassistant;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class MaterialWebImportApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void importsWebMaterialAndPersistsCleanTextChunks() throws Exception {
        String token = registerAndLogin(uniqueName("web-material-user"));
        HttpServer server = startPageServer("""
            <!doctype html>
            <html>
              <head>
                <title>Ignored title</title>
                <style>.hidden { color: red; }</style>
                <script>window.secret = "ignore me";</script>
              </head>
              <body>
                <article>
                  <h1>RAG course note</h1>
                  <p>RAG pipeline uses retrieved chunks to answer database questions.</p>
                </article>
              </body>
            </html>
            """);

        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/note";
            var importResult = mockMvc.perform(post("/api/materials/web")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "title": "Web RAG Note",
                          "sourceUrl": "%s"
                        }
                        """.formatted(url)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.title").value("Web RAG Note"))
                .andExpect(jsonPath("$.data.sourceType").value("WEB"))
                .andReturn();

            Long materialId = extractLong(importResult.getResponse().getContentAsString(), "id");

            // Web 导入现在先返回队列快照，真正的文本抽取、切片和索引在事务提交后消费处理队列。
            // 测试用户可见的最终状态时，应通过详情和片段接口验证资料已经完成处理。
            waitForMaterialParseSuccess(token, materialId);
            mockMvc.perform(get("/api/materials/{id}", materialId)
                    .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceUrl").value(url))
                .andExpect(jsonPath("$.data.originalName").value("note.html"))
                .andExpect(jsonPath("$.data.parseStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.data.chunkCount").value(1));

            mockMvc.perform(get("/api/materials/{id}/chunks", materialId)
                    .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].chunkText", containsString("RAG pipeline uses retrieved chunks")))
                .andExpect(jsonPath("$.data[0].chunkText", not(containsString("secret"))));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void importsWebMaterialWhenSchemeIsOmitted() throws Exception {
        String token = registerAndLogin(uniqueName("web-material-scheme-user"));
        HttpServer server = startPageServer("""
            <!doctype html>
            <html><body><main>Scheme omitted URLs are normalized before import.</main></body></html>
            """);

        try {
            String urlWithoutScheme = "127.0.0.1:" + server.getAddress().getPort() + "/note";
            mockMvc.perform(post("/api/materials/web")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "title": "No Scheme Web Note",
                          "sourceUrl": "%s"
                        }
                        """.formatted(urlWithoutScheme)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.sourceType").value("WEB"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void importsLinkedFileWithOriginalTypeAndServesOriginalFile() throws Exception {
        String token = registerAndLogin(uniqueName("linked-file-material-user"));
        HttpServer server = startFileServer(
            "linked-note.txt",
            "text/plain; charset=utf-8",
            "Linked file import keeps the original text file shape.".getBytes(StandardCharsets.UTF_8)
        );

        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/linked-note.txt";
            var importResult = mockMvc.perform(post("/api/materials/web")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "title": "Linked Text Note",
                          "sourceUrl": "%s"
                        }
                        """.formatted(url)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.title").value("Linked Text Note"))
                .andExpect(jsonPath("$.data.sourceType").value("TXT"))
                .andReturn();

            Long materialId = extractLong(importResult.getResponse().getContentAsString(), "id");

            // 链接文件同样会经过资料处理队列，上传响应只负责确认任务已创建。
            waitForMaterialParseSuccess(token, materialId);
            mockMvc.perform(get("/api/materials/{id}", materialId)
                    .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.originalName").value("linked-note.txt"))
                .andExpect(jsonPath("$.data.parseStatus").value("SUCCESS"));

            mockMvc.perform(get("/api/materials/{id}/file", materialId)
                    .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                    .string("Content-Type", containsString("text/plain")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                    .string("Linked file import keeps the original text file shape."));
        } finally {
            server.stop(0);
        }
    }

    private HttpServer startPageServer(String html) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/note", exchange -> {
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private HttpServer startFileServer(String fileName, String contentType, byte[] body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/" + fileName, exchange -> {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Content-Disposition", "inline; filename=\"" + fileName + "\"");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "%s",
                      "password": "12345678",
                      "nickname": "Web User"
                    }
                    """.formatted(username)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        var loginResult = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "%s",
                      "password": "12345678"
                    }
                    """.formatted(username)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.token").isNotEmpty())
            .andReturn();

        return extractString(loginResult.getResponse().getContentAsString(), "token");
    }

    private String uniqueName(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String extractString(String body, String fieldName) {
        Matcher matcher = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (!matcher.find()) {
            throw new IllegalStateException(fieldName + " not found in response: " + body);
        }
        return matcher.group(1);
    }

    private Long extractLong(String body, String fieldName) {
        Matcher matcher = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*(\\d+)").matcher(body);
        if (!matcher.find()) {
            throw new IllegalStateException(fieldName + " not found in response: " + body);
        }
        return Long.parseLong(matcher.group(1));
    }

    /** 资料处理队列是异步完成的，测试按用户最终可见状态短轮询到解析成功。 */
    private void waitForMaterialParseSuccess(String token, Long materialId) throws Exception {
        long deadline = System.currentTimeMillis() + 3_000;
        String lastBody = "";
        while (System.currentTimeMillis() < deadline) {
            var detailResult = mockMvc.perform(get("/api/materials/{id}", materialId)
                    .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
            lastBody = detailResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
            if ("SUCCESS".equals(extractString(lastBody, "parseStatus"))) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("material did not reach SUCCESS: " + lastBody);
    }
}
