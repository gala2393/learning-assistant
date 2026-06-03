package com.mytext.learningassistant.rag;

public record ChatImage(
    String dataUrl,
    String base64Data,
    String mediaType
) {

    public String resolvedMediaType() {
        if (mediaType != null && !mediaType.isBlank()) {
            return mediaType;
        }
        if (dataUrl == null || !dataUrl.startsWith("data:")) {
            return "";
        }
        int semicolonIndex = dataUrl.indexOf(';');
        if (semicolonIndex <= 5) {
            return "";
        }
        return dataUrl.substring(5, semicolonIndex);
    }
}
