# 智学引擎

智学引擎是一个面向学习资料管理、阅读和智能问答的全栈应用。系统支持资料上传解析、原文预览、边读边问、通用问答、图片问答、临时资料问答、RAG 检索增强、使用记录统计、管理员后台和可切换大模型配置。

项目采用 Spring Boot 后端和 React + Vite 前端，生产环境当前以 Docker Compose 部署到轻量应用服务器。

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

## 本地运行

### 后端

```bat
cd /d C:\Users\23931\Desktop\learning-assistant\backend
start-backend.cmd
```

后端默认运行在：

```text
http://127.0.0.1:8080
```

### 前端

```bat
cd /d C:\Users\23931\Desktop\learning-assistant\frontend
start-frontend.cmd
```

前端默认运行在：

```text
http://127.0.0.1:5174
```

Vite 会把 `/api` 代理到后端 `http://127.0.0.1:8080`。

## 配置

本地后端启动脚本按顺序读取：

1. `backend/.env`
2. `backend/.env.local`

其中 `.env.local` 会覆盖 `.env`。这两个文件都不应提交到 Git。

常见配置：

```env
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=8080
MYSQL_URL=jdbc:mysql://127.0.0.1:3306/learning_assistant?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
MYSQL_USERNAME=root
MYSQL_PASSWORD=<mysql-password>
APP_AUTH_SECRET=<long-random-secret>
APP_STORAGE_DIR=./data/learning-assistant-files
APP_CORS_ALLOWED_ORIGINS=http://localhost:5174,http://127.0.0.1:5174
```

LLM 配置示例：

```env
LLM_ENABLED=true
LLM_BASE_URL=https://www.codex2api.com
LLM_API_KEY=<your-api-key>
LLM_MODEL=gpt-5.5
LLM_API_FORMAT=responses
LLM_TIMEOUT=60s
```

`LLM_API_FORMAT` 可选值：

- `chat-completions`：OpenAI Chat Completions 兼容接口
- `responses`：OpenAI Responses API 兼容接口

用户自定义模型配置会保存在数据库中，前端不回显完整 API Key，只显示是否已保存。

管理员账号通过 `APP_ADMIN_BOOTSTRAP_ENABLED=true` 和 `APP_ADMIN_PASSWORD` 配置创建（密码需至少 12 位）。

前端生产构建默认使用：

```env
VITE_API_BASE=/api
```

## 构建与验证

前端构建：

```bat
cd /d C:\Users\23931\Desktop\learning-assistant\frontend
npm install
npm run build
```

后端测试：

```bat
cd /d C:\Users\23931\Desktop\learning-assistant\backend
.\mvnw test
```

常用快速验证：

```bat
cd /d C:\Users\23931\Desktop\learning-assistant\backend
.\mvnw "-Dtest=OpenAiCompatibleLlmClientTest,ThirdPartyLlmClientTest,RagStreamControllerTest" test
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
