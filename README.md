# 智学引擎

智学引擎是一个面向学习资料管理、阅读和智能问答的全栈应用。系统支持资料上传解析、原文预览、边读边问、通用问答、图片问答、临时资料问答、RAG 检索增强、使用记录统计、管理员后台和可切换大模型配置。

项目采用 Spring Boot 后端和 React + Vite 前端，生产环境当前以 Docker Compose 部署到轻量应用服务器。

## 最近更新

- 首页新增产品介绍页，未登录用户打开站点会先看到产品能力预览，可直接进入工作区或登录页。
- 资料总结升级为通用结构化摘要，支持摘要类型选择、来源跳转和用户整理版保存。

## 主要功能

- 账号体系：登录、注册、找回密码、个人信息、头像和密码修改
- 用户管控：管理员可修改用户角色、禁用用户登录，并显示禁用提示
- 资料管理：上传资料、解析进度、资料列表、详情、编辑、删除
- 原文件访问：支持安全打开原文件，避免直接暴露文件真实路径
- 文档阅读：资料列表、片段列表、原文预览、边读边问，适配桌面端、平板和手机端
- 智能问答：通用知识问答、资料绑定问答、问答历史、收藏
- 图片问答：支持上传图片或粘贴图片，后端按多模态请求发送给模型
- 临时资料问答：通用问答可临时上传 PDF、Word、PPT、Markdown、TXT 等文件作为当前对话上下文，不进入资料库
- 流式阅读保护：AI 回答持续输出时，用户上滑阅读已生成内容不会被自动拉回底部
- 流式输出：后端基于 SSE 逐段返回模型内容，减少长回答等待时间
- 模型切换：用户可保存多个自定义模型配置，支持测试连接、切换、删除
- 系统默认模型：支持 OpenAI Chat Completions、Anthropic Messages 和 OpenAI Responses API
- 使用限制：普通用户每日问答次数限制，管理员不限，前端显示剩余次数
- 使用记录：管理员可查看用户问答、资料上传等操作记录、模型名称和 token 消耗
- 系统日志：保留后台系统行为日志，使用记录独立展示
- RAG 评估：支持管理员维护评估集、运行评估、查看评估结果
- 向量检索：预留 Qdrant 向量库部署配置和 rerank 能力
- 安全加固：接口限流、登录验证码、HTTP 安全头、SSRF 防护、Token 版本失效、BCrypt 密码加密

## 技术栈

- 后端：Java 21，Spring Boot 3.5，Spring Data JPA，Flyway，MySQL
- 前端：React 18，Vite，TypeScript，React Router，TanStack Query
- UI：Tailwind CSS，Radix UI，Lucide Icons，Framer Motion
- 部署：Docker Compose，Nginx，MySQL 8
- 可选能力：Tesseract OCR，LibreOffice 文档转换，Qdrant 向量库

## 项目结构

```text
learning-assistant/
├── backend/              # Spring Boot 后端
├── frontend/             # React 前端
├── 开发教程/             # 项目开发与部署教程
├── upload-package/       # 本地生成的服务器上传包，已被 git 忽略
└── frontend-dist-upload/ # 本地生成的前端上传包，已被 git 忽略
```

## 页面路由

普通用户：

- `/login`、`/register`、`/forgot-password`：认证页面
- `/`：产品介绍首页
- `/workspace/chat`：智能问答和资料问答
- `/workspace/materials`：资料管理
- `/workspace/reader`：资料阅读与边读边问
- `/workspace/history`：问答历史
- `/workspace/favorites`：收藏列表
- `/workspace/summary`：资料总结

管理员：

- `/admin/dashboard`：后台仪表盘
- `/admin/users`：用户与角色管理
- `/admin/materials`：资料管理
- `/admin/evaluation`：RAG 评估
- `/admin/usage-records`：使用记录
- `/admin/logs`：系统日志

## 使用教程

下面是一份从零开始的本地运行教程，适合第一次从 GitHub 拉取项目的开发者。项目分为 `backend` 和 `frontend` 两部分：后端负责登录、资料解析、问答、RAG 和管理后台接口，前端负责页面展示。先启动 MySQL，再启动后端，最后启动前端。

### 1. 准备运行环境

请先安装这些基础工具：

- Git：用于拉取代码
- Java 21：后端运行环境
- Node.js 20 或更高版本：前端运行环境
- MySQL 8：项目数据库
- Maven：可选，仓库内置了 Maven Wrapper，Windows 可直接使用 `backend/mvnw.cmd`

这些增强能力可以后续再安装，本地首次运行可以先关闭：

- Redis：用于验证码、限流等短期状态存储
- Tesseract OCR：用于扫描 PDF 图片文字识别
- LibreOffice：用于 Word、PPT 等文档预览转换
- Qdrant：用于外部向量库检索

### 2. 拉取代码

```bash
git clone https://github.com/gala2393/learning-assistant.git
cd learning-assistant
```

如果你已经拉过代码，更新到最新版本：

```bash
git pull origin main
```

### 3. 创建 MySQL 数据库

登录 MySQL 后创建数据库：

```sql
CREATE DATABASE learning_assistant
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;
```

项目使用 Flyway 管理表结构。后端第一次启动时会自动执行 `backend/src/main/resources/db/migration` 下的迁移脚本，所以通常不需要手动建表。

### 4. 配置后端环境变量

复制后端环境变量模板：

```bash
cd backend
cp .env.example .env.local
```

Windows PowerShell 可以使用：

```powershell
cd backend
Copy-Item .env.example .env.local
```

然后编辑 `backend/.env.local`。本地首次运行建议先使用下面这组最小配置，把数据库、登录密钥、管理员账号和模型配置填好：

```env
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=8080

MYSQL_URL=jdbc:mysql://127.0.0.1:3306/learning_assistant?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
MYSQL_USERNAME=root
MYSQL_PASSWORD=你的MySQL密码

APP_AUTH_SECRET=请换成一段至少64位的随机字符串
APP_STORAGE_DIR=./data/learning-assistant-files
APP_CORS_ALLOWED_ORIGINS=http://localhost:5174,http://127.0.0.1:5174

APP_ADMIN_BOOTSTRAP_ENABLED=true
APP_ADMIN_USERNAME=admin
APP_ADMIN_PASSWORD=请换成至少12位的管理员密码
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

`LLM_API_FORMAT` 根据你的模型服务选择：

- `responses`：OpenAI Responses API 兼容接口
- `chat-completions`：OpenAI Chat Completions 兼容接口

如果你暂时没有大模型密钥，可以把 `LLM_ENABLED=false`，项目仍可启动和访问页面，但智能问答会使用后端兜底逻辑，效果不是完整 AI 问答。

如果你要启用资料语义检索，把 `EMBEDDING_ENABLED=true`，并配置：

```env
EMBEDDING_BASE_URL=https://api.voyageai.com
EMBEDDING_API_KEY=你的Embedding服务APIKey
EMBEDDING_MODEL=voyage-multimodal-3
```

注意：`backend/.env` 和 `backend/.env.local` 都包含真实密码或 API Key，绝对不要提交到 GitHub。

### 5. 启动后端

Windows 推荐使用项目自带脚本：

```bat
cd /d C:\你的路径\learning-assistant\backend
start-backend.cmd
```

也可以直接使用 Maven Wrapper：

```bat
cd /d C:\你的路径\learning-assistant\backend
.\mvnw.cmd spring-boot:run
```

macOS 或 Linux 可以使用：

```bash
cd backend
./mvnw spring-boot:run
```

后端默认地址：

```text
http://127.0.0.1:8080
```

健康检查地址：

```text
http://127.0.0.1:8080/api/health
```

如果启动失败，优先检查：

- MySQL 是否已启动
- `MYSQL_PASSWORD` 是否正确
- `learning_assistant` 数据库是否已创建
- `APP_AUTH_SECRET` 是否已填写
- 8080 端口是否被其他程序占用

### 6. 配置并启动前端

进入前端目录，复制环境变量模板：

```bash
cd frontend
cp .env.example .env.local
```

Windows PowerShell 可以使用：

```powershell
cd frontend
Copy-Item .env.example .env.local
```

本地开发推荐把 `frontend/.env.local` 写成：

```env
VITE_API_BASE=http://localhost:8080/api
```

安装依赖并启动前端：

```bash
npm install
npm run dev -- --host 127.0.0.1 --port 5174
```

Windows 也可以直接运行脚本，脚本会在没有 `node_modules` 时自动执行 `npm install`：

```bat
cd /d C:\你的路径\learning-assistant\frontend
start-frontend.cmd
```

前端默认地址：

```text
http://127.0.0.1:5174
```

Vite 开发服务会把 `/api` 代理到后端。默认代理目标是 `http://127.0.0.1:8080`，如果你修改了后端端口，可以设置：

```env
VITE_DEV_PROXY_TARGET=http://127.0.0.1:你的后端端口
```

### 7. 登录和初始化管理员

第一次启动时，如果 `APP_ADMIN_BOOTSTRAP_ENABLED=true` 且数据库里还没有对应管理员，后端会按下面配置创建管理员账号：

```env
APP_ADMIN_USERNAME=admin
APP_ADMIN_PASSWORD=请换成至少12位的管理员密码
APP_ADMIN_NICKNAME=管理员
```

启动成功后打开：

```text
http://127.0.0.1:5174/login
```

使用你配置的管理员账号登录。登录后可以进入后台管理用户、资料、评估集、使用记录和系统日志。

如果你只想普通用户体验，也可以在注册页创建普通账号。邮箱验证码默认关闭时，按当前后端配置和页面能力决定是否开放邮箱验证码流程。

### 8. 常见问题

后端提示数据库连接失败：

- 检查 MySQL 是否运行
- 检查数据库名是否是 `learning_assistant`
- 检查 `MYSQL_URL`、`MYSQL_USERNAME`、`MYSQL_PASSWORD`

后端提示 JWT 或认证配置错误：

- 检查 `APP_AUTH_SECRET` 是否已填写
- 建议使用 64 位以上随机字符串

前端页面能打开但接口报错：

- 确认后端 `http://127.0.0.1:8080/api/health` 正常
- 确认 `VITE_API_BASE` 或 Vite 代理目标指向后端
- 确认 `APP_CORS_ALLOWED_ORIGINS` 包含 `http://localhost:5174` 和 `http://127.0.0.1:5174`

智能问答没有真实模型效果：

- 检查 `LLM_ENABLED=true`
- 检查 `LLM_BASE_URL`、`LLM_API_KEY`、`LLM_MODEL`
- 根据模型服务选择正确的 `LLM_API_FORMAT`

上传资料后检索效果不好：

- 本地最小配置默认关闭 `EMBEDDING_ENABLED`
- 要获得完整 RAG 语义检索效果，需要配置 Embedding 服务
- Qdrant 是可选项，不开启时可先使用基础能力验证流程

管理员密码忘记：

- 开发环境可以直接在数据库中处理用户数据，或清空本地测试数据库后重新启动初始化
- 生产环境不要随意删除数据库，建议通过后台或安全脚本重置

### 9. 本地构建

前端构建：

```bash
cd frontend
npm install
npm run build
```

后端测试：

```bash
cd backend
./mvnw test
```

Windows 后端测试：

```bat
cd /d C:\你的路径\learning-assistant\backend
.\mvnw.cmd test
```

常用快速验证：

```bat
cd /d C:\你的路径\learning-assistant\backend
.\mvnw.cmd "-Dtest=OpenAiCompatibleLlmClientTest,ThirdPartyLlmClientTest,RagStreamControllerTest" test
```

## 上传与构建产物

服务器更新使用本地目录：

- `upload-package/`
- `frontend-dist-upload/`
- `frontend/dist/`

这些目录用于 MobaXterm 手动上传，不提交到 Git。前端上线文件以 `frontend/dist` 为准。

## Docker 部署结构

服务器目录约定：

```text
/opt/learning-assistant
├── docker-compose.yml
├── .env
├── repo
│   ├── backend
│   └── frontend
├── frontend-dist
├── mysql-data
├── app-data
└── nginx
```

Docker Compose 中：

- `mysql`：MySQL 8 数据库
- `backend`：Spring Boot 后端容器
- `nginx`：前端静态资源和 `/api` 反向代理

服务器根目录 `/opt/learning-assistant/.env` 是 Docker Compose 实际读取的环境变量文件。Docker 部署时，数据库地址应使用容器服务名：

```env
MYSQL_URL=jdbc:mysql://mysql:3306/learning_assistant?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
APP_STORAGE_DIR=/data/learning-assistant-files
```

## 更新已有服务器

本地先构建前端：

```bat
cd /d C:\Users\23931\Desktop\learning-assistant\frontend
npm run build
```

通过 MobaXterm SFTP 上传：

- `upload-package/backend` 内容到 `/opt/learning-assistant/repo/backend`
- `upload-package/frontend` 内容到 `/opt/learning-assistant/repo/frontend`
- `frontend/dist` 内容到 `/opt/learning-assistant/frontend-dist`

服务器更新命令：

```bash
cd /opt/learning-assistant
docker compose stop backend nginx
docker compose up -d --build backend
docker compose up -d nginx
docker compose ps
```

查看后端日志：

```bash
docker compose logs -f backend
```

验证：

```bash
curl http://127.0.0.1/api/health
```

## GitHub 说明

仓库不提交真实运行密钥、上传包、前端构建产物、测试截图、服务器私钥和本地运行产物。

已加入忽略或从版本库移除的本地产物包括：

- `.codex-run/`
- `output/`
- `test-results/`
- `upload-package/`
- `upload-package-latest/`
- `frontend-dist-upload/`
- `frontend/dist/`
- `*.ppk`、`*.key`、`*.zip`、`*.tar.gz`

已移除 Vercel/Railway 自动部署配置：

- `vercel.json`
- `railway.json`
- `backend/railway.json`

当前推荐部署方式是手动上传到轻量应用服务器后使用 Docker Compose 更新。

## 注意事项

- `backend/.env`、`backend/.env.local`、服务器 `/opt/learning-assistant/.env` 都必须妥善保管
- 用户自定义模型 API Key 当前由后端保存并用于模型调用，前端只显示是否已配置
- 服务器升级时不要删除 `/opt/learning-assistant/mysql-data` 和 `/opt/learning-assistant/app-data`
- 如果修改了 `LLM_API_KEY`、`LLM_MODEL` 或 `LLM_API_FORMAT`，需要重启后端容器
- 浏览器上线验证时建议使用 `Ctrl + F5` 强制刷新前端静态资源
- 管理员账号通过 `APP_ADMIN_BOOTSTRAP_ENABLED=true` 和 `APP_ADMIN_PASSWORD` 配置创建
- `APP_AUTH_SECRET` 必须使用长随机字符串，Token 中包含 tokenVersion 用于密码修改后使旧 Token 失效
- 接口限流配置在 `app.security.rate-limit.*` 下，可按需调整
