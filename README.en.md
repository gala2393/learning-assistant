# Learning Assistant

[中文](README.md) | [English](README.en.md)

Live site: [https://learnstudy.cloud](https://learnstudy.cloud)

Learning Assistant is a full-stack study platform for managing learning materials, reading documents, and asking AI-powered questions. It supports PDF, scanned PDF, Word, PowerPoint, TXT, Markdown, and HTML materials, with document reading, material-based Q&A, temporary file context, RAG retrieval, streaming answers, answer interruption, favorites, history, summaries, and an admin console.

This README focuses only on local setup so first-time users can install, configure, start, and troubleshoot the project quickly.

## Features

- Account system: registration, login, password reset, profile, avatar, and password update.
- Material management: upload, chunked upload, parsing status, list, details, deletion, reparse, and index rebuild.
- Document parsing: PDF, scanned PDF, Word, PowerPoint, TXT, Markdown, and HTML.
- Reader: PDF page preview, basic TXT/Word layout, continue reading, ask while reading, and source navigation.
- AI chat: general chat, material chat, temporary material context, image questions, streaming output, and pause output.
- RAG: keyword retrieval, vector retrieval, hybrid retrieval, rerank, and Qdrant vector store.
- Admin console: user management, material management, usage records, system logs, RAG evaluation, and dependency checks.

## Tech Stack

- Backend: Java 21, Spring Boot 3.5, Spring Data JPA, Flyway, MySQL 8
- Frontend: React 18, Vite, TypeScript, React Router, TanStack Query
- UI: Tailwind CSS, Radix UI, Lucide Icons, Framer Motion
- Document processing: PDFBox, Poppler, Tesseract OCR, LibreOffice, Ghostscript
- Retrieval: BM25, Embedding, Qdrant, rerank

## Project Structure

```text
learning-assistant/
├── backend/                 # Spring Boot backend
├── frontend/                # React + Vite frontend
├── 更新说明.md              # Release notes in Chinese
├── README.en.md             # English README
└── README.md
```

## Local Requirements

Required:

- Git
- Java 21
- Node.js 20 or newer
- MySQL 8

Recommended later as needed:

- Redis 7 for captcha, rate limiting, and short-lived state.
- Qdrant for vector retrieval.
- Tesseract OCR for scanned PDFs.
- Poppler for PDF page rendering.
- LibreOffice for Word/PPT preview conversion.
- Ghostscript for large PDF compression.

For the first local run, Git, Java, Node.js, and MySQL are enough. AI, OCR, vector search, and Office preview can be enabled later.

## Local Setup

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

Flyway migrations under `backend/src/main/resources/db/migration` run automatically when the backend starts.

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

- `LLM_ENABLED=false` uses a limited local fallback, which is useful for first-time setup.
- Real AI answers require `LLM_BASE_URL`, `LLM_API_KEY`, and `LLM_MODEL`.
- Vector retrieval requires an embedding service and Qdrant.
- `APP_ADMIN_BOOTSTRAP_ENABLED=true` creates the initial admin account. After that account is created, change it to `false`.

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

Expected response:

```json
{"status":"ok","service":"智学引擎"}
```

### 5. Configure Frontend

Open a new terminal:

```bash
cd frontend
cp .env.example .env.local
```

Windows PowerShell:

```powershell
cd frontend
Copy-Item .env.example .env.local
```

Confirm `frontend/.env.local` contains:

```env
VITE_API_BASE=http://localhost:8080/api
```

### 6. Start Frontend

```bash
cd frontend
npm install
npm run dev -- --host 127.0.0.1 --port 5174
```

Open:

```text
http://127.0.0.1:5174
```

The default admin account depends on your `backend/.env.local` values:

```text
Username: admin
Password: the value of APP_ADMIN_PASSWORD
```

## Optional Capabilities

### AI Chat

To only test uploads, the reader, and page flows, keep:

```env
LLM_ENABLED=false
```

To enable real model answers, edit `backend/.env.local`:

```env
LLM_ENABLED=true
LLM_BASE_URL=https://your-model-endpoint
LLM_API_KEY=your_model_api_key
LLM_MODEL=your_model_name
LLM_API_FORMAT=responses
```

Restart the backend after changing these values.

### Vector Retrieval

Vector retrieval improves RAG recall quality. You need:

- A working embedding service.
- A locally reachable Qdrant service.

Then configure:

```env
EMBEDDING_ENABLED=true
EMBEDDING_BASE_URL=https://your-embedding-endpoint
EMBEDDING_API_KEY=your_embedding_api_key
EMBEDDING_MODEL=your_embedding_model

VECTOR_STORE_ENABLED=true
VECTOR_STORE_PROVIDER=qdrant
VECTOR_STORE_BASE_URL=http://127.0.0.1:6333
VECTOR_STORE_COLLECTION=learning_assistant_chunks
```

If you do not need vector search yet, keep:

```env
EMBEDDING_ENABLED=false
VECTOR_STORE_ENABLED=false
```

### OCR and Document Preview

Scanned PDF OCR requires Tesseract:

```env
OCR_ENABLED=true
OCR_COMMAND=tesseract
OCR_LANG=eng+chi_sim
```

Word/PPT preview conversion requires LibreOffice:

```env
DOCUMENT_PREVIEW_CONVERTER_ENABLED=true
DOCUMENT_PREVIEW_CONVERTER_COMMAND=soffice
```

Large PDF compression requires Ghostscript:

```env
PDF_COMPRESSION_ENABLED=true
PDF_COMPRESSION_COMMAND=gs
```

If these tools are not installed locally, keep the related switches set to `false`. The app can still start, but those enhanced capabilities will be unavailable.

## Useful URLs

- Frontend: `http://127.0.0.1:5174`
- Backend health check: `http://127.0.0.1:8080/api/health`
- Live site: `https://learnstudy.cloud`

## Common Commands

Build backend:

```powershell
cd backend
.\mvnw.cmd -DskipTests package
```

Build frontend:

```bash
cd frontend
npm run build
```

Check Git status:

```bash
git status --short
```

## Troubleshooting

### 1. Backend cannot connect to MySQL

Make sure MySQL is running and verify `MYSQL_URL`, `MYSQL_USERNAME`, and `MYSQL_PASSWORD` in `backend/.env.local`.

### 2. Frontend API requests fail

Make sure the backend is running and `frontend/.env.local` contains:

```env
VITE_API_BASE=http://localhost:8080/api
```

Also make sure backend CORS includes:

```env
http://localhost:5174,http://127.0.0.1:5174
```

### 3. Admin login fails

Before the first backend start, set:

```env
APP_ADMIN_BOOTSTRAP_ENABLED=true
APP_ADMIN_USERNAME=admin
APP_ADMIN_PASSWORD=your_password
```

If the admin user already exists, changing `.env.local` may not overwrite the old password. Reset the user in the database or start with a clean local database.

### 4. Scanned PDFs have no OCR result

Make sure Tesseract is installed and available:

```bash
tesseract --version
```

Then enable:

```env
OCR_ENABLED=true
```

### 5. Word or PPT preview is unavailable

Make sure LibreOffice is installed and available:

```bash
soffice --version
```

Then enable:

```env
DOCUMENT_PREVIEW_CONVERTER_ENABLED=true
```

### 6. RAG answers are slow or retrieval is weak

The minimal local setup does not require vector search. To improve material Q&A quality, configure Embedding and Qdrant, then rebuild indexes for existing materials.

## Release Notes

See [更新说明.md](更新说明.md).
