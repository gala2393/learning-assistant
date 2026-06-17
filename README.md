# 智学引擎 Learning Assistant

智学引擎是一个面向学习资料管理、资料阅读和智能问答的全栈项目。它支持 PDF、图片型 PDF、Word、PPT、TXT、Markdown 等资料上传解析，提供资料阅读、边读边问、普通智能问答、临时资料多轮上下文、RAG 检索增强、图片问答、收藏、历史、总结和管理员后台。

项目由 Spring Boot 后端、React 前端、MySQL、Redis、Qdrant、Nginx 和 Docker Compose 组成。你可以用本地开发模式启动，也可以用 Docker Compose 一键拉起完整服务。

## 功能概览

- 账号体系：注册、登录、找回密码、个人信息、头像、修改密码。
- 管理后台：用户管理、资料管理、使用记录、系统日志、RAG 评估、依赖检查。
- 资料管理：上传、分片上传、解析状态、资料列表、详情、删除、重新解析、重建索引。
- 资料解析：支持 PDF、图片型 PDF、Word、PPT、TXT、Markdown、HTML。
- 图片型 PDF：支持页面先预览，OCR 后台分批补齐，避免大 PDF 一次性 OCR 卡死。
- 阅读器：PDF 页面预览、TXT/Word 基础排版、继续阅读、边读边问、来源跳转。
- 智能问答：普通问答、资料问答、临时资料上下文、图片问答、流式输出、暂停输出。
- RAG：关键词检索、向量检索、混合召回、rerank、Qdrant 向量库、历史资料重建索引。
- 体验优化：长输入按真实可用上限截断，资料页面图片支持缓存，临时资料跨轮保留上下文。

## 技术栈

- 后端：Java 21、Spring Boot 3.5、Spring Data JPA、Flyway、MySQL 8
- 前端：React 18、Vite、TypeScript、React Router、TanStack Query
- UI：Tailwind CSS、Radix UI、Lucide Icons、Framer Motion
- 文档处理：PDFBox、Poppler、Tesseract OCR、LibreOffice、Ghostscript
- 检索：BM25、Embedding、Qdrant、rerank
- 部署：Docker Compose、Nginx、Docker volume

## 项目结构

```text
learning-assistant/
├── backend/                 # Spring Boot 后端
├── frontend/                # React + Vite 前端
├── deploy/                  # 部署环境变量模板
├── docker-compose.yml       # Docker Compose 完整部署
├── DOCKER_DEPLOY.md         # 服务器 Docker 部署补充说明
├── 更新说明.md              # 版本更新记录
└── README.md
```

## 不要提交的文件

仓库已经通过 `.gitignore` 忽略常见敏感文件和构建产物。请不要提交：

- `.env`、`.env.local`、`deploy/server.env`
- `*.pem`、`*.key`、`*.crt`、`*.ppk`
- SSL 证书目录，例如 `learnstudy.cloud_nginx（重要，是ssl）/`
- `backend/target/`、`frontend/dist/`、`node_modules/`
- 数据目录、上传资料、备份目录、部署压缩包

如果你准备公开仓库，提交前建议执行：

```bash
git status --short
git ls-files | grep -Ei '(\.env|\.pem|\.key|\.crt|server\.env|ssl|node_modules|target|dist)'
```

Windows PowerShell：

```powershell
git status --short
git ls-files | Select-String -Pattern '(\.env|\.pem|\.key|\.crt|server\.env|ssl|node_modules|target|dist)'
```

## 本地开发环境准备

必须安装：

- Git
- Java 21
- Node.js 20 或更高版本
- MySQL 8

推荐安装：

- Redis 7：验证码、限流和短期状态存储
- Qdrant：向量检索
- Tesseract OCR：图片型 PDF OCR
- Poppler：PDF 页面渲染
- LibreOffice：Word/PPT 转 PDF 预览
- Ghostscript：大 PDF 压缩

Windows 用户可以先只安装 Git、Java、Node.js、MySQL，把 OCR、LibreOffice、Qdrant 作为后续增强项。

## 方式一：本地开发启动

### 1. 克隆项目

```bash
git clone https://github.com/gala2393/learning-assistant.git
cd learning-assistant
```

### 2. 创建 MySQL 数据库

登录 MySQL 后执行：

```sql
CREATE DATABASE learning_assistant
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;
```

项目使用 Flyway 管理表结构，后端启动时会自动执行 `backend/src/main/resources/db/migration` 下的迁移脚本。

### 3. 配置后端环境变量

复制模板：

```bash
cd backend
cp .env.example .env.local
```

Windows PowerShell：

```powershell
cd backend
Copy-Item .env.example .env.local
```

打开 `backend/.env.local`，至少修改这些配置：

```env
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=8080

MYSQL_URL=jdbc:mysql://127.0.0.1:3306/learning_assistant?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
MYSQL_USERNAME=root
MYSQL_PASSWORD=你的MySQL密码

APP_AUTH_SECRET=请换成至少32位随机字符串
APP_STORAGE_DIR=./data/learning-assistant-files
APP_CORS_ALLOWED_ORIGINS=http://localhost:5174,http://127.0.0.1:5174

APP_ADMIN_BOOTSTRAP_ENABLED=true
APP_ADMIN_USERNAME=admin
APP_ADMIN_PASSWORD=请换成安全密码
APP_ADMIN_NICKNAME=管理员

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

说明：

- `LLM_ENABLED=false` 时，问答会走有限的兜底逻辑，适合先把项目跑起来。
- 要使用真实 AI 问答，需要配置 `LLM_BASE_URL`、`LLM_API_KEY`、`LLM_MODEL`。
- 要使用向量检索，需要配置 Embedding 和 Qdrant。
- `APP_ADMIN_BOOTSTRAP_ENABLED=true` 会在首次启动时创建管理员账号，创建成功后生产环境建议改为 `false`。

### 4. 启动后端

Windows：

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

macOS / Linux：

```bash
cd backend
./mvnw spring-boot:run
```

后端健康检查：

```text
http://127.0.0.1:8080/api/health
```

如果返回类似下面内容，说明后端启动成功：

```json
{"status":"ok","service":"智学引擎"}
```

### 5. 配置前端环境变量

打开新终端：

```bash
cd frontend
cp .env.example .env.local
```

Windows PowerShell：

```powershell
cd frontend
Copy-Item .env.example .env.local
```

确保 `frontend/.env.local` 中有：

```env
VITE_API_BASE=http://localhost:8080/api
```

### 6. 启动前端

```bash
cd frontend
npm install
npm run dev -- --host 127.0.0.1 --port 5174
```

浏览器打开：

```text
http://127.0.0.1:5174
```

默认管理员账号取决于你的 `backend/.env.local`：

```text
账号：admin
密码：你在 APP_ADMIN_PASSWORD 中设置的密码
```

## 方式二：Docker Compose 启动完整环境

Docker 方式适合服务器部署，也适合本地快速体验完整依赖。

### 1. 安装 Docker

需要安装：

- Docker Engine 或 Docker Desktop
- Docker Compose v2

检查版本：

```bash
docker --version
docker compose version
```

### 2. 准备环境变量

```bash
cp deploy/server.env.example deploy/server.env
```

Windows PowerShell：

```powershell
Copy-Item deploy/server.env.example deploy/server.env
```

编辑 `deploy/server.env`，至少修改：

```env
MYSQL_ROOT_PASSWORD=请换成数据库root密码
MYSQL_PASSWORD=请换成数据库root密码
REDIS_PASSWORD=请换成Redis密码

APP_AUTH_SECRET=请换成至少32位随机字符串
APP_ADMIN_BOOTSTRAP_ENABLED=true
APP_ADMIN_USERNAME=admin
APP_ADMIN_PASSWORD=请换成安全密码

LLM_ENABLED=true
LLM_BASE_URL=https://你的模型服务地址
LLM_API_KEY=你的模型APIKey
LLM_MODEL=你的模型名称
LLM_API_FORMAT=responses
```

如果只是先验证项目能启动，可以临时设置：

```env
LLM_ENABLED=false
EMBEDDING_ENABLED=false
VECTOR_STORE_ENABLED=false
```

### 3. 启动容器

在项目根目录执行：

```bash
docker compose up -d --build
```

查看状态：

```bash
docker compose ps
```

查看后端日志：

```bash
docker compose logs --tail=160 backend
```

访问：

```text
http://127.0.0.1
```

健康检查：

```bash
curl -i http://127.0.0.1/api/health
```

### 4. Docker 数据持久化

Compose 会创建这些 volume：

- `mysql_data`：MySQL 数据
- `redis_data`：Redis 数据
- `qdrant_data`：Qdrant 向量数据
- `app_files`：用户上传资料、预览图、OCR 中间文件等

不要执行下面这类命令，除非你明确要清空所有数据：

```bash
docker compose down -v
docker volume rm mysql_data
docker volume rm app_files
```

## Qdrant 向量检索说明

Qdrant 能提升 RAG 检索阶段的速度和语义召回质量，但不能加速大模型生成文字本身。

启用方式：

```env
EMBEDDING_ENABLED=true
EMBEDDING_BASE_URL=https://你的Embedding服务地址
EMBEDDING_API_KEY=你的Embedding API Key
EMBEDDING_MODEL=你的Embedding模型

VECTOR_STORE_ENABLED=true
VECTOR_STORE_PROVIDER=qdrant
VECTOR_STORE_BASE_URL=http://qdrant:6333
VECTOR_STORE_COLLECTION=learning_assistant_chunks
```

如果是在本地非 Docker 模式运行后端，`VECTOR_STORE_BASE_URL` 通常写：

```env
VECTOR_STORE_BASE_URL=http://127.0.0.1:6333
```

已经上传过的资料不需要重新解析，但需要在管理员后台触发“重建向量索引”，让已有 chunk 写入 Qdrant。

## OCR、PDF 和 Office 预览

这些能力是可选增强项：

- 图片型 PDF OCR：需要 Tesseract
- PDF 页面预览：需要 Poppler 或后端内置渲染能力
- Word/PPT 预览：需要 LibreOffice 的 `soffice`
- 大 PDF 压缩：需要 Ghostscript 的 `gs`

本地先跑通项目时可以关闭：

```env
OCR_ENABLED=false
DOCUMENT_PREVIEW_CONVERTER_ENABLED=false
PDF_COMPRESSION_ENABLED=false
```

服务器或 Docker 环境建议开启：

```env
OCR_ENABLED=true
OCR_LANG=eng+chi_sim
DOCUMENT_PREVIEW_CONVERTER_ENABLED=true
PDF_COMPRESSION_ENABLED=true
```

管理员后台有依赖检查页面，可以确认 Poppler、Tesseract、LibreOffice、Ghostscript 是否可用。

## 常用页面

普通用户：

- `/login`：登录
- `/register`：注册
- `/forgot-password`：找回密码
- `/workspace/chat`：智能问答
- `/workspace/materials`：资料管理
- `/workspace/reader`：资料阅读与边读边问
- `/workspace/history`：问答历史
- `/workspace/favorites`：收藏
- `/workspace/summary`：知识总结

管理员：

- `/admin/dashboard`：后台仪表盘
- `/admin/users`：用户管理
- `/admin/materials`：资料管理
- `/admin/evaluation`：RAG 评估
- `/admin/usage-records`：使用记录
- `/admin/logs`：系统日志

## 常用命令

后端构建：

```bash
cd backend
./mvnw -DskipTests package
```

Windows：

```powershell
cd backend
.\mvnw.cmd -DskipTests package
```

前端构建：

```bash
cd frontend
npm run build
```

Docker 重启：

```bash
docker compose up -d --build
docker compose ps
```

## 常见问题

### 1. 后端启动时报 MySQL 连接失败

检查：

- MySQL 是否启动
- 数据库 `learning_assistant` 是否已创建
- `MYSQL_URL`、`MYSQL_USERNAME`、`MYSQL_PASSWORD` 是否正确
- MySQL 是否允许当前用户从本机连接

### 2. 前端登录接口 404 或跨域

检查：

- 后端是否在 `8080` 端口运行
- `frontend/.env.local` 是否设置 `VITE_API_BASE=http://localhost:8080/api`
- 后端 `APP_CORS_ALLOWED_ORIGINS` 是否包含 `http://localhost:5174`

### 3. AI 问答不可用

检查：

- `LLM_ENABLED` 是否为 `true`
- `LLM_BASE_URL`、`LLM_API_KEY`、`LLM_MODEL` 是否正确
- 模型服务是否兼容当前配置的 `LLM_API_FORMAT`

### 4. 图片型 PDF 没有 OCR

检查：

- `OCR_ENABLED=true`
- 已安装 Tesseract
- `OCR_LANG` 包含可用语言包，例如 `eng+chi_sim`
- 管理员后台依赖检查是否通过

### 5. Word/PPT 无法预览

检查：

- 已安装 LibreOffice
- `DOCUMENT_PREVIEW_CONVERTER_ENABLED=true`
- `DOCUMENT_PREVIEW_CONVERTER_COMMAND=soffice`

### 6. 上传资料后 RAG 回答慢

可能原因：

- 首次解析、OCR、索引仍在后台处理
- 未启用 Qdrant，只能走较慢的本地向量或关键词检索
- 大模型生成速度慢，这不是 Qdrant 能解决的部分

建议：

- 开启 Qdrant
- 给历史资料重建向量索引
- 关闭高成本 query expansion 或 HyDE
- 优先保证资料解析和索引状态完成

## 发布记录

完整更新记录见：

```text
更新说明.md
```
