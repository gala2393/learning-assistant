# Docker 启动与服务器部署

本文档说明两种启动方式：本地只启动 Qdrant，以及服务器一键启动完整项目。

## 1. 本地只启动 Qdrant

适合你当前本地开发：MySQL、后端、前端仍然按现有脚本启动，只把向量库放进 Docker。

```powershell
cd C:\path\to\learning-assistant
docker compose -f backend\docker-compose.qdrant.yml up -d
```

然后在 `backend/.env` 或 `backend/.env.local` 中开启：

```env
VECTOR_STORE_ENABLED=true
VECTOR_STORE_PROVIDER=qdrant
VECTOR_STORE_BASE_URL=http://127.0.0.1:6333
VECTOR_STORE_COLLECTION=learning_assistant_chunks_voyage_multimodal_3
VECTOR_STORE_TIMEOUT=10s
```

重启后端后，进入管理后台的“系统依赖”应能看到 Qdrant 已启用。

> 注意：如果 Docker Desktop 没有启动，命令会报 `failed to connect to the docker API`。先打开 Docker Desktop，等左下角变成 running，再执行命令。

## 2. 服务器完整 Docker Compose 部署

服务器上推荐使用根目录的 `docker-compose.yml`，它会启动：

- MySQL 8
- Redis 7
- Qdrant
- Spring Boot 后端
- Nginx 前端

步骤：

```bash
cd learning-assistant
cp deploy/server.env.example deploy/server.env
```

编辑 `deploy/server.env`，至少替换这些值：

```env
MYSQL_ROOT_PASSWORD=你的MySQLRoot密码
REDIS_PASSWORD=你的Redis密码
APP_AUTH_SECRET=32位以上随机字符串
APP_ADMIN_PASSWORD=管理员密码
LLM_BASE_URL=你的模型接口地址
LLM_API_KEY=你的模型Key
LLM_MODEL=你的模型名称
EMBEDDING_API_KEY=你的Embedding服务Key
APP_CORS_ALLOWED_ORIGINS=https://你的域名
```

启动：

```bash
docker compose up -d --build
```

查看状态：

```bash
docker compose ps
docker compose logs -f backend
```

关闭：

```bash
docker compose down
```

生产环境不要执行 `docker compose down -v`，避免误删数据卷。

## 3. 更新线上前端静态文件

如果只是改了前端页面，可以使用仓库里的安全部署脚本：

```powershell
.\deploy\deploy-frontend.ps1 -Server root@your-server-ip -SshKey .\your_key.pem
```

这个脚本会完成：

- 使用线上环境变量构建前端，接口地址固定为 `/api`。
- 检查构建产物里不能出现 `localhost:8080`。
- 上传 `frontend/dist` 到服务器。
- 在服务器上备份 `/opt/learning-assistant/frontend-dist`。
- 保留 `frontend-dist` 目录本身，只清空并替换目录内容。
- reload Nginx，并检查容器内 `/usr/share/nginx/html/index.html` 是否存在。

不要在服务器上直接执行 `rm -rf frontend-dist && mv frontend-dist.new frontend-dist`。Nginx 容器通过 Docker bind mount 挂载这个目录，删除并重建目录会让运行中的容器继续指向旧目录，表现为首页 403、子路由 500。正确方式是保留目录，只替换里面的文件。

## 4. 服务器 Nginx 合规与安全配置

服务器 Nginx 配置模板见：`deploy/nginx-default.conf`。它包含：

- HTTP 自动 301 跳转到 HTTPS。
- `learnstudy.cloud` 和 `www.learnstudy.cloud` 同时可访问。
- 静态首页和静态资源安全响应头：
  - `Strict-Transport-Security`
  - `X-Content-Type-Options`
  - `Referrer-Policy`
  - `X-Frame-Options`
  - `Content-Security-Policy`
- React Router history fallback。
- `/api` 反向代理到后端。
- 资料阅读器图片私有缓存。

修改服务器 Nginx 配置后先测试再 reload：

```bash
docker exec learning-nginx nginx -t
docker exec learning-nginx nginx -s reload
```

## 5. Qdrant 对速度的影响

Qdrant 能加速的是“从大量资料片段中找相似片段”的阶段。资料数量越多、片段越多，收益越明显。

它不会加速这些阶段：

- PDF OCR
- Word/PDF 解析
- Embedding 生成
- HyDE / 查询扩展额外调用模型
- 大模型最终生成答案

如果你感觉 RAG 回答慢，优先排查：

1. 是否开启了 `QUERY_EXPANSION_HYDE_ENABLED=true`，它会额外调用模型。
2. Embedding 接口是否慢。
3. 大模型输出是否慢。
4. 资料片段是否已经写入 Qdrant。

服务器配置里我默认把 HyDE 设为关闭：

```env
QUERY_EXPANSION_HYDE_ENABLED=false
```

这是为了先把交付速度和稳定性做上去，后续再按质量需要打开。
