# 智学引擎 Learning Assistant

[中文](README.md) | [English](README.en.md)

在线访问：[https://learnstudy.cloud](https://learnstudy.cloud)

公开页面：

- [使用协议](https://learnstudy.cloud/terms)
- [隐私政策](https://learnstudy.cloud/privacy)
- [关于我们](https://learnstudy.cloud/about)

智学引擎是一个面向学习资料管理、资料阅读和智能问答的全栈学习项目。它支持 PDF、图片型 PDF、Word、PPT、TXT、Markdown 等资料上传解析，提供资料阅读、边读边问、普通智能问答、临时资料多轮上下文、RAG 检索增强、图片问答、收藏、历史、总结和管理员后台。

这份 README 只说明如何在本地把项目跑起来，帮助新用户尽快完成安装、配置、启动和基础排错。

## 功能概览

- 账号体系：注册、登录、找回密码、个人信息、头像、修改密码。
- 资料管理：上传、分片上传、解析状态、资料列表、详情、删除、重新解析、重建索引。
- 资料解析：支持 PDF、图片型 PDF、Word、PPT、TXT、Markdown、HTML。
- 阅读器：PDF 页面预览、TXT/Word 基础排版、继续阅读、边读边问、来源跳转。
- 智能问答：普通问答、资料问答、临时资料上下文、图片问答、流式输出、暂停输出。
- RAG：关键词检索、向量检索、混合召回、rerank、Qdrant 向量库。
- 管理后台：用户管理、资料管理、使用记录、系统日志、RAG 评估、依赖检查。

## 技术栈

- 后端：Java 21、Spring Boot 3.5、Spring Data JPA、Flyway、MySQL 8
- 前端：React 18、Vite、TypeScript、React Router、TanStack Query
- UI：Tailwind CSS、Radix UI、Lucide Icons、Framer Motion
- 文档处理：PDFBox、Poppler、Tesseract OCR、LibreOffice、Ghostscript
- 检索：BM25、Embedding、Qdrant、rerank

## 项目结构

```text
learning-assistant/
├── backend/                 # Spring Boot 后端
├── frontend/                # React + Vite 前端
├── 更新说明.md              # 版本更新记录
├── README.en.md             # English README
└── README.md
```

## 本地环境准备

必须安装：

- Git
- Java 21
- Node.js 20 或更高版本
- MySQL 8

推荐后续按需安装：

- Redis 7：验证码、限流和短期状态存储。
- Qdrant：向量检索。
- Tesseract OCR：图片型 PDF OCR。
- Poppler：PDF 页面渲染。
- LibreOffice：Word/PPT 转 PDF 预览。
- Ghostscript：大 PDF 压缩。

第一次本地启动可以先只安装 Git、Java、Node.js 和 MySQL，把 AI、OCR、向量库、Office 预览作为后续增强项。

## 本地启动步骤

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

打开 `backend/.env.local`，至少修改下面这些配置：

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

- `LLM_ENABLED=false` 时，问答会走有限的本地兜底逻辑，适合先把项目跑起来。
- 要使用真实 AI 问答，需要配置 `LLM_BASE_URL`、`LLM_API_KEY`、`LLM_MODEL`。
- 要使用向量检索，需要配置 Embedding 服务和 Qdrant。
- `APP_ADMIN_BOOTSTRAP_ENABLED=true` 会在首次启动时创建管理员账号，创建成功后建议改为 `false`，避免重复初始化。

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

确认 `frontend/.env.local` 中有：

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

默认管理员账号取决于你在 `backend/.env.local` 中设置的值：

```text
账号：admin
密码：APP_ADMIN_PASSWORD 中配置的密码
```

## 可选能力配置

### AI 问答

如果只想先体验资料上传、阅读器和页面流程，可以保持：

```env
LLM_ENABLED=false
```

如果要启用真实模型问答，修改 `backend/.env.local`：

```env
LLM_ENABLED=true
LLM_BASE_URL=https://你的模型服务地址
LLM_API_KEY=你的模型APIKey
LLM_MODEL=你的模型名称
LLM_API_FORMAT=responses
```

修改后重启后端。

### 向量检索

向量检索用于提升 RAG 资料问答的召回质量。需要同时准备：

- 一个可用的 Embedding 服务。
- 一个本地可访问的 Qdrant 服务。

然后修改：

```env
EMBEDDING_ENABLED=true
EMBEDDING_BASE_URL=https://你的Embedding服务地址
EMBEDDING_API_KEY=你的Embedding APIKey
EMBEDDING_MODEL=你的Embedding模型名称

VECTOR_STORE_ENABLED=true
VECTOR_STORE_PROVIDER=qdrant
VECTOR_STORE_BASE_URL=http://127.0.0.1:6333
VECTOR_STORE_COLLECTION=learning_assistant_chunks
```

如果暂时不配置向量库，可以保持：

```env
EMBEDDING_ENABLED=false
VECTOR_STORE_ENABLED=false
```

### OCR 和文档预览

图片型 PDF 的文字识别依赖 Tesseract OCR：

```env
OCR_ENABLED=true
OCR_COMMAND=tesseract
OCR_LANG=eng+chi_sim
```

Word/PPT 预览转换依赖 LibreOffice：

```env
DOCUMENT_PREVIEW_CONVERTER_ENABLED=true
DOCUMENT_PREVIEW_CONVERTER_COMMAND=soffice
```

大 PDF 压缩依赖 Ghostscript：

```env
PDF_COMPRESSION_ENABLED=true
PDF_COMPRESSION_COMMAND=gs
```

如果本机没有安装这些工具，请保持对应开关为 `false`，项目仍然可以启动，只是相关增强能力不可用。

## 常用页面

- 前端首页：`http://127.0.0.1:5174`
- 后端健康检查：`http://127.0.0.1:8080/api/health`
- 在线站点：`https://learnstudy.cloud`

## 常用命令

后端编译：

```powershell
cd backend
.\mvnw.cmd -DskipTests package
```

前端构建：

```bash
cd frontend
npm run build
```

查看 Git 状态：

```bash
git status --short
```

如果需要更新线上前端静态文件，请参考 [DOCKER_DEPLOY.md](DOCKER_DEPLOY.md) 中的安全部署脚本说明。脚本会保留服务器上的 `frontend-dist` 目录本身，只替换目录内容，避免 Docker bind mount 失效导致 Nginx 首页 403 或子路由 500。

## 常见问题

### 1. 后端启动失败，提示数据库连接失败

检查 MySQL 是否已启动，并确认 `backend/.env.local` 中的 `MYSQL_URL`、`MYSQL_USERNAME`、`MYSQL_PASSWORD` 正确。

### 2. 前端请求接口失败

确认后端已启动，且 `frontend/.env.local` 中的 `VITE_API_BASE` 是：

```env
VITE_API_BASE=http://localhost:8080/api
```

同时确认后端 `APP_CORS_ALLOWED_ORIGINS` 包含：

```env
http://localhost:5174,http://127.0.0.1:5174
```

### 3. 登录不了管理员账号

首次启动前需要设置：

```env
APP_ADMIN_BOOTSTRAP_ENABLED=true
APP_ADMIN_USERNAME=admin
APP_ADMIN_PASSWORD=你的密码
```

如果数据库里已经创建过管理员，后续修改 `.env.local` 不一定会覆盖旧密码，可以在数据库中重置用户，或新建一个干净的本地数据库重新启动。

### 4. 图片型 PDF 没有 OCR 结果

确认本机已安装 Tesseract，并且命令行能执行：

```bash
tesseract --version
```

然后在 `backend/.env.local` 中启用：

```env
OCR_ENABLED=true
```

### 5. Word 或 PPT 没有预览

确认本机已安装 LibreOffice，并且命令行能执行：

```bash
soffice --version
```

然后启用：

```env
DOCUMENT_PREVIEW_CONVERTER_ENABLED=true
```

### 6. RAG 回答慢或召回不准

本地最小启动可以不启用向量库。要提升资料问答质量，需要配置 Embedding 和 Qdrant，并对已有资料重建索引。

## 发布记录

详细更新内容见：[更新说明.md](更新说明.md)
