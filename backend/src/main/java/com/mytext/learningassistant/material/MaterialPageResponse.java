package com.mytext.learningassistant.material;

import java.util.List;

public record MaterialPageResponse(
    Integer pageNo,
    Float width,
    Float height,
    String imageName,
    List<Long> chunkIds,
    String renderStatus
) {
}
