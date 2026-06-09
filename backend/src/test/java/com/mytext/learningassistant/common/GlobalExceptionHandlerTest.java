package com.mytext.learningassistant.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerTest {

    @Test
    void mapsMaxUploadSizeExceededToPayloadTooLarge() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var response = handler.handleMaxUploadSize(new MaxUploadSizeExceededException(500L * 1024 * 1024));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertEquals(413, response.getBody().code());
        assertEquals("文件太大，请控制在500MB以内后再导入。", response.getBody().message());
    }

    @Test
    void usesTwoGigabyteFallbackWhenMaxUploadSizeIsUnknown() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var response = handler.handleMaxUploadSize(new MaxUploadSizeExceededException(-1L));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertEquals(413, response.getBody().code());
        assertEquals("文件太大，请控制在2GB以内后再导入。", response.getBody().message());
    }

    @Test
    void mapsMissingStaticResourceToNotFound() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var response = handler.handleNoResourceFound(new NoResourceFoundException(HttpMethod.GET, "favicon.ico"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(404, response.getBody().code());
        assertEquals("请求的资源不存在", response.getBody().message());
    }
}
