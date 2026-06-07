FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /workspace
COPY . .
RUN set -eux; \
    app_dir="/workspace"; \
    if [ ! -f "$app_dir/mvnw" ] && [ -f "/workspace/backend/mvnw" ]; then app_dir="/workspace/backend"; fi; \
    cd "$app_dir"; \
    sed -i 's/\r$//' mvnw; \
    chmod +x mvnw; \
    ./mvnw -q -DskipTests clean package; \
    cp target/*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre-jammy

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ghostscript \
        libreoffice \
        tesseract-ocr \
        tesseract-ocr-eng \
        tesseract-ocr-chi-sim \
        fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=builder /workspace/app.jar /app/app.jar

ENV SPRING_PROFILES_ACTIVE=prod
ENV APP_STORAGE_DIR=/data/learning-assistant-files

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
