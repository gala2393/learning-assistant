# 智学引擎 Learning Assistant

智学引擎是一个面向课程资料管理、阅读和智能问答的全栈学习助手。系统支持资料上传解析、PDF/TXT/Word 阅读、边读边问、普通智能问答、临时资料多轮上下文、RAG 检索增强、图片问答、收藏、历史记录、总结、管理员后台和生产环境 Docker 部署。

当前版本重点提升了大文件资料处理稳定性、图片型 PDF 分批 OCR、临时资料上下文、多轮问答、暂停输出、Qdrant 向量检索和服务器部署可维护性。

## 最近更新

### v1.3.0

- 资料上传链路升级：删除资料后再次上传同一文件会重新上传，不再因为旧上传会话直接秒成功。
- 大 PDF 解析改为分批处理，图片型 PDF 支持按页/小批次 OCR，并在资料卡片展示上传、文本、OCR、索引等阶段状态。
- 修复大文件解析卡在 `0%`、`85%` 或无限渲染的问题，解析失败时尽量给出明确失败原因。
- TXT、Word、PDF 阅读体验优化，PDF 即使 OCR 未完成也可以先预览页面，并提示文字识别状态。
- 普通智能问答支持临时资料跨轮上下文，已发送的临时资料不会反复显示在输入框上方。
- 流式回答支持暂停输出，减少长回答无法中断的问题。
- 增加输入上限相关配置，避免较长问题或上下文直接触发服务端错误。
- 边读边问会保存当前资料上下文，切换模块再返回时尽量恢复原资料和页码。
- 后台增加 Qdrant 依赖检查和资料向量索引重建入口。
- 生产部署支持 Docker named volume，MySQL、Redis、资料文件和 Qdrant 数据不再依赖临时容器层。
- 新增公开版 Docker 部署说明 `DOCKER_DEPLOY.md`，记录容器化部署、Qdrant 和数据卷注意事项。

## 主要功能

- 账号体系：登录、注册、找回密码、个人信息、头像和密码修改。
- 用户管理：管理员可修改用户角色、禁用用户登录，并显示禁用提示。
- 资料管理：上传资料、解析进度、资料列表、详情、编辑、删除、重新解析。
- 大文件上传：支持分片上传、上传会话、秒传判定修正和删除后重传。
- 资料解析：支持 PDF、TXT、Markdown、Word、PPT 等资料格式。
- 图片型 PDF：支持页面预览和分批 OCR，避免一次性处理数百页导致任务卡死。
- 阅读器：支持资料列表、片段列表、PDF 页面预览、TXT/Word 基础排版、继续阅读和边读边问。
- 智能问答：支持普通问答、资料绑定问答、图片问答、临时资料问答和多轮上下文。
- 临时资料：用户在普通问答上传的临时资料会作为对话上下文保留，不进入资料库。
- 流式输出：后端基于 SSE 返回模型内容，前端支持暂停输出。
- RAG 检索：支持关键词检索、向量检索、rerank 和上下文拼装。
- Qdrant 向量库：支持 Docker 部署、健康检查和历史资料向量重建。
- 收藏与历史：支持收藏回答、查看历史问答和继续上下文。
- 知识总结：支持围绕资料生成总结和查看总结内容。
- 管理后台：支持仪表盘、用户管理、资料管理、使用记录、系统日志和 RAG 评估。
- 安全加固：接口限流、登录验证码、HTTP 安全头、CSRF 防护、Token 版本失效、BCrypt 密码加密。

## 技术栈

- 后端：Java 21、Spring Boot 3.5、Spring Data JPA、Flyway、MySQL 8
- 前端：React 18、Vite、TypeScript、React Router、TanStack Query
- UI：Tailwind CSS、Radix UI、Lucide Icons、Framer Motion
- 检索：RAG、BM25、Embedding、Qdrant、rerank
- 文档处理：PDFBox、Poppler、Tesseract OCR、LibreOffice
- 部署：Docker Compose、Nginx、Docker named volume

## 项目结构

```text
learning-assistant/
├── backend/                     # Spring Boot 后端
├── frontend/                    # React 前端
├── deploy/                      # 服务器部署配置示例
├── DOCKER_DEPLOY.md             # Docker 部署说明
├── 更新说明.md                  # 发布更新记录
└── README.md
```

## 本地开发环境

请先安装：

- Git
- Java 21
- Node.js 20 或更高版本
- MySQL 8
- Maven 可选，项目内置 Maven Wrapper

可选增强能力：

- Redis：验证码、限流、短期状态存储
- Tesseract OCR：图片型 PDF 文字识别
- LibreOffice：Word/PPT 预览转换
- Qdrant：外部向量检索

## 创建数据库

```sql
CREATE DATABASE learning_assistant
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;
```

项目使用 Flyway 管理表结构，后端启动时会自动执行 `backend/src/main/resources/db/migration` 下的迁移脚本。

## 后端配置

复制环境变量模板：

```bash
cd backend
cp .env.example .env.local
```

Windows PowerShell：

```powershell
cd backend
Copy-Item .env.example .env.local
```

最小本地配置示例：

```env
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=8080

MYSQL_URL=jdbc:mysql://127.0.0.1:3306/learning_assistant?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
MYSQL_USERNAME=root
MYSQL_PASSWORD=你的MySQL密码

APP_AUTH_SECRET=请换成至少64位随机字符串
APP_STORAGE_DIR=./data/learning-assistant-files
APP_CORS_ALLOWED_ORIGINS=http://localhost:5174,http://127.0.0.1:5174

APP_ADMIN_BOOTSTRAP_ENABLED=true
APP_ADMIN_USERNAME=admin
APP_ADMIN_PASSWORD=请换成安全密码
APP_ADMIN_NICKNAME=管理员

EMAIL_CODE_ENABLED=false
APP_REDIS_ENABLED=false

LLM_ENABLED=true
LLM_BASE_URL=你的大模型接口地址
LLM_API_KEY=你的大模型APIKey
LLM_MODEL=你的模型名称
LLM_API_FORMAT=responses
LLM_TIMEOUT=60s

EMBEDDING_ENABLED=false
VECTOR_STORE_ENABLED=false
RERANKER_ENABLED=true
RERANKER_PROVIDER=local

OCR_ENABLED=false
DOCUMENT_PREVIEW_CONVERTER_ENABLED=false
PDF_COMPRESSION_ENABLED=false
```

不要提交真实 `.env`、`.env.local`、服务器密钥或 API Key。

## 启动后端

Windows：

```bat
cd /d C:\你的路径\learning-assistant\backend
start-backend.cmd
```

或：

```bat
cd /d C:\你的路径\learning-assistant\backend
.\mvnw.cmd spring-boot:run
```

macOS / Linux：

```bash
cd backend
./mvnw spring-boot:run
```

健康检查：

```text
http://127.0.0.1:8080/api/health
```

## 前端配置与启动

复制前端环境变量：

```bash
cd frontend
cp .env.example .env.local
```

Windows PowerShell：

```powershell
cd frontend
Copy-Item .env.example .env.local
```

本地开发配置：

```env
VITE_API_BASE=http://localhost:8080/api
```

安装依赖并启动：

```bash
npm install
npm run dev -- --host 127.0.0.1 --port 5174
```

前端地址：

```text
http://127.0.0.1:5174
```

## Docker 部署

仓库提供 Docker Compose 部署配置。生产环境建议使用 Docker named volume 保存数据：

- `learning_mysql_data`
- `learning_redis_data`
- `learning_app_data`
- `learning_qdrant_data`

Docker 部署、Qdrant 和环境变量说明详见：

```text
DOCKER_DEPLOY.md
```

首次部署前必须准备生产 `.env`，不要使用本地 `.env.local`。

常用命令：

```bash
cd /opt/learning-assistant
docker compose up -d --build
docker compose ps
docker compose logs --tail=160 backend
curl -sS -i http://127.0.0.1/api/health
```

不要执行：

```bash
docker compose down -v
docker volume rm learning_mysql_data
docker volume rm learning_app_data
```

## Qdrant 说明

Qdrant 可提升 RAG 检索阶段的速度和语义召回质量，但不能加速大模型生成文字本身。

启用 Qdrant 后，历史资料不需要重新解析，但需要重建向量索引。管理员可在后台资料管理中触发“重建向量索引”。

健康检查示例：

```bash
docker run --rm \
  --network learning-assistant_learning-net \
  curlimages/curl:8.8.0 \
  -sS http://qdrant:6333/healthz
```

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

## 验证命令

后端编译：

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

生产健康检查：

```bash
curl -sS -i http://服务器地址/api/health
```

## 发布记录

完整发布记录见：

```text
更新说明.md
```
