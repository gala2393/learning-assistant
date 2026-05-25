package com.mytext.learningassistant.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

class GlobalExceptionHandlerTest {

    @Test
    void mapsMaxUploadSizeExceededToPayloadTooLarge() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var response = handler.handleMaxUploadSize(new MaxUploadSizeExceededException(500L * 1024 * 1024));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertEquals(413, response.getBody().code());
        assertEquals("文件太大，请控制在 500MB 以内后再导入。", response.getBody().message());
    }

    @Test
    void usesFiveHundredMegabyteFallbackWhenMaxUploadSizeIsUnknown() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var response = handler.handleMaxUploadSize(new MaxUploadSizeExceededException(-1L));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertEquals(413, response.getBody().code());
        assertEquals("文件太大，请控制在 500MB 以内后再导入。", response.getBody().message());
    }
}
