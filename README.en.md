# Learning Assistant

[中文](README.md) | [English](README.en.md)

Live site: [https://learnstudy.cloud](https://learnstudy.cloud)

Learning Assistant is a full-stack study platform for managing learning materials, reading documents, and asking AI-powered questions. It supports PDF, scanned PDF, Word, PowerPoint, TXT, Markdown, and HTML materials, with document reading, material-based Q&A, temporary file context, RAG retrieval, streaming answers, answer interruption, favorites, history, summaries, and an admin console.

The project is built with Spring Boot, React, MySQL, Redis, Qdrant, Nginx, and Docker Compose.

## Features

- Account system: registration, login, password reset, profile, avatar, password update.
- Admin console: user management, material management, usage records, logs, RAG evaluation, dependency checks.
- Material management: upload, chunked upload, parsing status, listing, details, deletion, reparse, index rebuild.
- Document parsing: PDF, scanned PDF, Word, PowerPoint, TXT, Markdown, HTML.
- Scanned PDFs: preview pages first, run OCR in small background batches to avoid blocking large files.
- Reader: PDF page preview, basic TXT/Word layout, continue reading, ask while reading, source navigation.
- AI chat: general chat, material chat, temporary material context, image questions, streaming output, pause output.
- RAG: keyword retrieval, vector retrieval, hybrid retrieval, rerank, Qdrant vector store, historical index rebuild.
- Usability improvements: realistic input truncation, cached material page images, multi-turn temporary material context.

## Tech Stack

- Backend: Java 21, Spring Boot 3.5, Spring Data JPA, Flyway, MySQL 8
- Frontend: React 18, Vite, TypeScript, React Router, TanStack Query
- UI: Tailwind CSS, Radix UI, Lucide Icons, Framer Motion
- Document processing: PDFBox, Poppler, Tesseract OCR, LibreOffice, Ghostscript
- Retrieval: BM25, Embedding, Qdrant, rerank
- Deployment: Docker Compose, Nginx, Docker volumes

## Project Structure

```text
learning-assistant/
├── backend/                 # Spring Boot backend
├── frontend/                # React + Vite frontend
├── deploy/                  # Deployment environment templates
├── docker-compose.yml       # Full Docker Compose deployment
├── DOCKER_DEPLOY.md         # Additional Docker deployment notes
├── 更新说明.md              # Release notes in Chinese
└── README.md
```

## Local Development

Required:

- Git
- Java 21
- Node.js 20 or newer
- MySQL 8

Recommended:

- Redis 7 for captcha, rate limiting, and short-lived state
- Qdrant for vector retrieval
- Tesseract OCR for scanned PDFs
- Poppler for PDF page rendering
- LibreOffice for Word/PPT preview conversion
- Ghostscript for large PDF compression

### 1. Clone

```bash
git clone https://github.com/gala2393/learning-assistant.git
cd learning-assistant
```

### 2. Create MySQL Database

```sql
CREATE DATABASE learning_assistant
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;
```

Flyway migrations under `backend/src/main/resources/db/migration` will run automatically when the backend starts.

### 3. Configure Backend

```bash
cd backend
cp .env.example .env.local
```

Windows PowerShell:

```powershell
cd backend
Copy-Item .env.example .env.local
```

Edit `backend/.env.local`:

```env
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=8080

MYSQL_URL=jdbc:mysql://127.0.0.1:3306/learning_assistant?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
MYSQL_USERNAME=root
MYSQL_PASSWORD=your_mysql_password

APP_AUTH_SECRET=replace_with_32_chars_or_longer_random_secret
APP_STORAGE_DIR=./data/learning-assistant-files
APP_CORS_ALLOWED_ORIGINS=http://localhost:5174,http://127.0.0.1:5174

APP_ADMIN_BOOTSTRAP_ENABLED=true
APP_ADMIN_USERNAME=admin
APP_ADMIN_PASSWORD=replace_with_secure_password
APP_ADMIN_NICKNAME=Admin

EMAIL_CODE_ENABLED=false
APP_REDIS_ENABLED=false

LLM_ENABLED=false
EMBEDDING_ENABLED=false
VECTOR_STORE_ENABLED=false
RERANKER_ENABLED=true
RERANKER_PROVIDER=local

OCR_ENABLED=false
DOCUMENT_PREVIEW_CONVERTER_ENABLED=false
PDF_COMPRESSION_ENABLED=false
```

Notes:

- `LLM_ENABLED=false` is useful for first-time bootstrapping. Real AI answers require `LLM_BASE_URL`, `LLM_API_KEY`, and `LLM_MODEL`.
- Vector retrieval requires an embedding service and Qdrant.
- After the initial admin account is created, set `APP_ADMIN_BOOTSTRAP_ENABLED=false` in production.

### 4. Start Backend

Windows:

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

macOS / Linux:

```bash
cd backend
./mvnw spring-boot:run
```

Health check:

```text
http://127.0.0.1:8080/api/health
```

### 5. Configure and Start Frontend

```bash
cd frontend
cp .env.example .env.local
```

Windows PowerShell:

```powershell
cd frontend
Copy-Item .env.example .env.local
```

Set:

```env
VITE_API_BASE=http://localhost:8080/api
```

Install dependencies and start:

```bash
npm install
npm run dev -- --host 127.0.0.1 --port 5174
```

Open:

```text
http://127.0.0.1:5174
```

## Docker Compose

Docker Compose starts MySQL, Redis, Qdrant, the Spring Boot backend, and the frontend Nginx server.

### 1. Prepare Environment

```bash
cp deploy/server.env.example deploy/server.env
```

Windows PowerShell:

```powershell
Copy-Item deploy/server.env.example deploy/server.env
```

Edit `deploy/server.env`:

```env
MYSQL_ROOT_PASSWORD=replace_with_mysql_root_password
MYSQL_PASSWORD=replace_with_mysql_root_password
REDIS_PASSWORD=replace_with_redis_password

APP_AUTH_SECRET=replace_with_32_chars_or_longer_random_secret
APP_ADMIN_BOOTSTRAP_ENABLED=true
APP_ADMIN_USERNAME=admin
APP_ADMIN_PASSWORD=replace_with_secure_password

LLM_ENABLED=true
LLM_BASE_URL=https://your-model-endpoint
LLM_API_KEY=your_model_api_key
LLM_MODEL=your_model_name
LLM_API_FORMAT=responses
```

For a startup-only check, you can temporarily disable external AI services:

```env
LLM_ENABLED=false
EMBEDDING_ENABLED=false
VECTOR_STORE_ENABLED=false
```

### 2. Start Locally With Docker

```bash
docker compose up -d --build
docker compose ps
docker compose logs --tail=160 backend
curl -i http://127.0.0.1/api/health
```

Open:

```text
http://127.0.0.1
```

## Server Deployment

The following generic deployment flow assumes Ubuntu, Debian, CentOS, Rocky Linux, or a similar Linux server.

### 1. Install Docker

Ubuntu / Debian:

```bash
sudo apt update
sudo apt install -y git ca-certificates curl
curl -fsSL https://get.docker.com | sudo sh
sudo systemctl enable --now docker
docker compose version
```

CentOS / Rocky Linux:

```bash
sudo yum install -y git yum-utils
curl -fsSL https://get.docker.com | sudo sh
sudo systemctl enable --now docker
docker compose version
```

### 2. Pull the Project

```bash
sudo mkdir -p /opt/learning-assistant
sudo chown -R "$USER":"$USER" /opt/learning-assistant
git clone https://github.com/gala2393/learning-assistant.git /opt/learning-assistant
cd /opt/learning-assistant
```

### 3. Configure

```bash
cp deploy/server.env.example deploy/server.env
nano deploy/server.env
```

Important values:

```env
MYSQL_ROOT_PASSWORD=replace_with_strong_password
MYSQL_PASSWORD=replace_with_same_mysql_password
REDIS_PASSWORD=replace_with_strong_password
APP_AUTH_SECRET=replace_with_32_chars_or_longer_random_secret
APP_ADMIN_PASSWORD=replace_with_initial_admin_password
APP_CORS_ALLOWED_ORIGINS=https://your-domain,http://your-domain
LLM_ENABLED=true
LLM_BASE_URL=https://your-model-endpoint
LLM_API_KEY=your_model_api_key
LLM_MODEL=your_model_name
```

### 4. Start and Verify

```bash
docker compose up -d --build
docker compose ps
docker compose logs --tail=160 backend
curl -i http://127.0.0.1/api/health
```

If port 80 is open:

```text
http://server-ip
```

## Domain and HTTPS

The frontend container already runs Nginx over HTTP. For HTTPS, terminate TLS on the host, a cloud load balancer, CDN, or Caddy, and proxy requests to the frontend container.

Recommended host-Nginx setup:

1. Bind the frontend container to localhost:

```env
WEB_PORT=127.0.0.1:8088
APP_CORS_ALLOWED_ORIGINS=https://your-domain
```

2. Recreate the frontend:

```bash
docker compose up -d --build frontend
```

3. Add a host Nginx reverse proxy:

```nginx
server {
    listen 80;
    server_name your-domain;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name your-domain;

    ssl_certificate /path/to/fullchain.crt;
    ssl_certificate_key /path/to/private.key;

    client_max_body_size 2g;

    location / {
        proxy_pass http://127.0.0.1:8088;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
    }
}
```

4. Validate and reload:

```bash
sudo nginx -t
sudo systemctl reload nginx
curl -I https://your-domain
curl -i https://your-domain/api/health
```

## Updating a Server Deployment

```bash
cd /opt/learning-assistant
git pull origin main
docker compose up -d --build
docker compose ps
curl -i http://127.0.0.1/api/health
```

Frontend-only:

```bash
docker compose up -d --build frontend
```

Backend-only:

```bash
docker compose up -d --build backend
```

Logs:

```bash
docker compose logs --tail=160 backend
docker compose logs --tail=160 frontend
```

## Data Persistence and Backup

Compose creates these volumes:

- `mysql_data`: MySQL data
- `redis_data`: Redis data
- `qdrant_data`: Qdrant vector data
- `app_files`: uploaded materials, previews, OCR intermediate files

Back up MySQL:

```bash
mkdir -p backups
set -a
. deploy/server.env
set +a
docker compose exec -T mysql mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" learning_assistant > backups/mysql-$(date +%Y%m%d%H%M%S).sql
```

Back up uploaded files:

```bash
mkdir -p backups
docker volume ls
docker run --rm \
  -v learning-assistant_app_files:/data:ro \
  -v "$PWD/backups:/backup" \
  alpine tar -czf /backup/app-files-$(date +%Y%m%d%H%M%S).tar.gz -C /data .
```

Do not use `docker compose down -v` unless you intentionally want to delete all persisted data.

## Qdrant

Qdrant speeds up the retrieval phase of RAG, especially when you have many material chunks. It does not speed up OCR, parsing, embedding generation, HyDE/query expansion, or final LLM text generation.

Already uploaded materials do not need to be parsed again after enabling Qdrant, but their vector index should be rebuilt from the admin console.

## Useful Pages

User:

- `/login`
- `/register`
- `/workspace/chat`
- `/workspace/materials`
- `/workspace/reader`
- `/workspace/history`
- `/workspace/favorites`
- `/workspace/summary`

Admin:

- `/admin/dashboard`
- `/admin/users`
- `/admin/materials`
- `/admin/evaluation`
- `/admin/usage-records`
- `/admin/logs`

## Common Commands

Backend build:

```bash
cd backend
./mvnw -DskipTests package
```

Frontend build:

```bash
cd frontend
npm run build
```

Docker rebuild:

```bash
docker compose up -d --build
docker compose ps
```

