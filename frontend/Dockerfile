FROM node:20-alpine AS builder

WORKDIR /app

# 先复制依赖清单，利用 Docker layer cache 加快后续构建。
COPY package*.json ./
RUN npm ci

COPY . .

# 容器化部署默认使用同源 /api，由 Nginx 反向代理到后端。
ARG VITE_API_BASE=/api
ENV VITE_API_BASE=${VITE_API_BASE}

RUN npm run build

FROM nginx:1.27-alpine

# 使用项目内 Nginx 配置支持 React Router history fallback 和 /api 代理。
COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80
