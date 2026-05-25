# Learning Assistant

Learning Assistant 是一个面向课程学习与资料管理的全栈应用，支持学习资料上传、预览、解析、总结、收藏，以及基于 RAG 的问答与对话检索。项目由 Spring Boot 后端和 React + Vite 前端组成，前后端分离运行。

## 主要功能

- 学习资料管理：上传、分页浏览、详情查看、编辑、删除
- Web 资料导入：从网页链接导入学习材料
- 文档预览：PDF、Office 文档等可视化预览
- OCR 识别：支持图片或扫描件文字提取
- 资料总结：对学习材料生成摘要
- RAG 问答：围绕资料内容进行检索增强问答
- 对话历史：查看、重命名、管理历史会话
- 收藏功能：收藏常用问答或资料
- 账号体系：登录、注册、个人信息、密码修改
- 管理后台：用户管理、资料管理、系统日志、仪表盘

## 技术栈

- 后端：Java 21，Spring Boot 3.5，Spring Data JPA，MySQL
- 前端：React 18，Vite，TypeScript，React Router，TanStack Query
- UI：Tailwind CSS，Radix UI，Lucide Icons，Framer Motion

## 项目结构

```text
learning-assistant/
├── backend/              # Spring Boot 后端
├── frontend/             # React 前端
├── upload-package/       # 导出用的干净打包目录
└── prepare-upload.ps1    # 生成上传包
```

## 页面

- `/login`、`/register`：登录与注册
- `/workspace/chat`：RAG 对话
- `/workspace/materials`：资料管理
- `/workspace/reader`：资料阅读与解析
- `/workspace/history`：问答历史
- `/workspace/favorites`：收藏列表
- `/workspace/summary`：资料总结
- `/admin/dashboard`：后台仪表盘
- `/admin/users`：用户管理
- `/admin/materials`：资料管理
- `/admin/logs`：系统日志

## 本地运行

### 后端

```bat
cd /d C:\Users\23931\Desktop\learning-assistant\backend
start-backend.cmd
```

### 前端

```bat
cd /d C:\Users\23931\Desktop\learning-assistant\frontend
start-frontend.cmd
```

前端默认运行在 `http://127.0.0.1:5174/`，并通过 Vite 代理把 `/api` 转发到后端 `http://127.0.0.1:8080`。

## 配置

后端使用 `backend/.env.local` 读取本地配置，建议基于 `backend/.env.example` 复制后修改。

常见配置项：

- `MYSQL_URL`、`MYSQL_USERNAME`、`MYSQL_PASSWORD`
- `APP_STORAGE_DIR`
- `APP_CORS_ALLOWED_ORIGINS`
- `LLM_ENABLED`、`LLM_BASE_URL`、`LLM_API_KEY`、`LLM_MODEL`
- `OCR_ENABLED`、`OCR_COMMAND`
- `DOCUMENT_PREVIEW_CONVERTER_COMMAND`

前端默认只需要：

```env
VITE_API_BASE=/api
```

## 构建

### 前端

```bat
cd /d C:\Users\23931\Desktop\learning-assistant\frontend
npm run build
```

### 后端

```bat
cd /d C:\Users\23931\Desktop\learning-assistant\backend
.\mvnw test
```

## 上传包

生成适合上传的干净目录：

```bat
cd /d C:\Users\23931\Desktop\learning-assistant
powershell -NoProfile -ExecutionPolicy Bypass -File .\prepare-upload.ps1
```

生成结果会排除本地密钥、缓存、构建产物、日志和临时文件。

## 说明

- 后端默认端口：`8080`
- 前端默认端口：`5174`
- 支持 MySQL、OCR、文档预览和可配置 LLM 接入

