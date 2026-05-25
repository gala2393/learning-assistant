package com.mytext.learningassistant.material;

import java.nio.file.Path;

public record MaterialFileResource(
    Path path,
    String fileName,
    String contentType,
    long contentLength
) {
}
