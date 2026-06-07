# Backend Deployment on Railway

Use Railway for the Spring Boot backend.

## Railway service settings

- Root Directory: `backend`
- Builder: Dockerfile
- Dockerfile: `Dockerfile`
- Health check path: `/api/health`
- Volume mount path: `/data`

`railway.json` is included so Railway can pick up the Dockerfile and health check.

## Required environment variables

```env
SPRING_PROFILES_ACTIVE=prod
JPA_DDL_AUTO=update

MYSQL_URL=jdbc:mysql://<MYSQL_HOST>:<MYSQL_PORT>/<MYSQL_DATABASE>?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
MYSQL_USERNAME=<MYSQL_USER>
MYSQL_PASSWORD=<MYSQL_PASSWORD>

APP_AUTH_SECRET=<long-random-secret>
APP_STORAGE_DIR=/data/learning-assistant-files
APP_CORS_ALLOWED_ORIGINS=https://<your-vercel-domain>,https://<your-project>.vercel.app,https://*.vercel.app

EMAIL_CODE_ENABLED=true
EMAIL_CODE_DEFAULT_PROVIDER=qq
EMAIL_CODE_QQ_HOST=smtp.qq.com
EMAIL_CODE_QQ_PORT=465
EMAIL_CODE_QQ_USERNAME=<your-sender@qq.com>
EMAIL_CODE_QQ_PASSWORD=<qq-smtp-authorization-code>
EMAIL_CODE_QQ_FROM=<your-sender@qq.com>
EMAIL_CODE_NETEASE_HOST=smtp.163.com
EMAIL_CODE_NETEASE_PORT=465
EMAIL_CODE_NETEASE_USERNAME=<your-sender@163.com>
EMAIL_CODE_NETEASE_PASSWORD=<163-smtp-authorization-code>
EMAIL_CODE_NETEASE_FROM=<your-sender@163.com>
EMAIL_CODE_TTL_SECONDS=300
EMAIL_CODE_EMAIL_COOLDOWN_SECONDS=60
EMAIL_CODE_IP_HOURLY_LIMIT=10

LLM_ENABLED=true
LLM_BASE_URL=<provider-base-url>
LLM_API_KEY=<provider-key>
LLM_MODEL=<provider-model>

EMBEDDING_ENABLED=false
OCR_ENABLED=true
OCR_LANG=eng+chi_sim
DOCUMENT_PREVIEW_CONVERTER_ENABLED=true
DOCUMENT_PREVIEW_CONVERTER_COMMAND=soffice
```

Railway provides `PORT` automatically, and `application-prod.yml` uses it.

## MySQL

Create a Railway MySQL service and copy its connection values into the backend variables above. Keep `JPA_DDL_AUTO=update` for first deployment unless you manage schema migrations separately.

## Persistent files

Mount a Railway Volume at `/data`. Uploaded files and rendered previews are stored under:

```env
APP_STORAGE_DIR=/data/learning-assistant-files
```

Without a volume, uploads may disappear after redeploys.

## Email verification

QQ mail:

```env
SMTP_HOST=smtp.qq.com
SMTP_PORT=465
SMTP_USERNAME=yourname@qq.com
SMTP_PASSWORD=<QQ SMTP authorization code>
EMAIL_CODE_FROM=yourname@qq.com
```

163 mail:

```env
SMTP_HOST=smtp.163.com
SMTP_PORT=465
SMTP_USERNAME=yourname@163.com
SMTP_PASSWORD=<163 SMTP authorization code>
EMAIL_CODE_FROM=yourname@163.com
```

## Smoke test

1. Visit `https://<railway-domain>/api/health`.
2. Confirm the frontend can register and log in.
3. Test email-code login.
4. Upload and preview a PDF/DOCX.
5. Confirm chat and summary features work with your LLM provider variables.
