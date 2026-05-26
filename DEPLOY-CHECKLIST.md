# Vercel + Railway Deployment Checklist

## Target architecture

- Frontend: Vercel, root directory `frontend`
- Backend: Railway, root directory `backend`
- Database: Railway MySQL
- Persistent files: Railway Volume mounted at `/data`
- Runtime secrets: only in Railway/Vercel environment variables

## Railway backend

1. Create a Railway project.
2. Add a MySQL service.
3. Add a backend service from this Git repository.
4. Set the backend root directory to `backend`.
5. Railway will use `backend/railway.json` and `backend/Dockerfile`.
6. Add a Railway Volume mounted at `/data`.
7. Generate a public Railway domain for the backend.

Required backend variables:

```env
SPRING_PROFILES_ACTIVE=prod
JPA_DDL_AUTO=update

MYSQL_URL=jdbc:mysql://<MYSQL_HOST>:<MYSQL_PORT>/<MYSQL_DATABASE>?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
MYSQL_USERNAME=<MYSQL_USER>
MYSQL_PASSWORD=<MYSQL_PASSWORD>

APP_AUTH_SECRET=<long-random-secret>
APP_STORAGE_DIR=/data/learning-assistant-files
APP_CORS_ALLOWED_ORIGINS=https://<your-vercel-domain>,https://*.vercel.app

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

LLM_ENABLED=true
LLM_BASE_URL=<provider-base-url>
LLM_API_KEY=<provider-api-key>
LLM_MODEL=<provider-model>

EMBEDDING_ENABLED=false
OCR_ENABLED=true
OCR_LANG=eng+chi_sim
DOCUMENT_PREVIEW_CONVERTER_ENABLED=true
```

Railway injects `PORT` automatically. The production backend config now reads `PORT` first, then falls back to `SERVER_PORT`.

## Vercel frontend

1. Import the same Git repository in Vercel.
2. Set Root Directory to `frontend`.
3. Build Command: `npm run build`
4. Output Directory: `dist`
5. Set this environment variable:

```env
VITE_API_BASE=https://<your-railway-backend-domain>/api
```

Do not hard-code a Railway domain in `vercel.json`. The frontend uses `VITE_API_BASE` at build time.

## Final smoke test

After both deployments finish:

- Open `https://<railway-backend>/api/health`
- Open the Vercel URL and register/login
- Test email verification login
- Upload a PDF and confirm preview pages load
- Upload a DOCX and confirm conversion works
- Ask a question in the chat workspace
- Restart the Railway backend and confirm uploaded files still exist

## Security checks

- No real `.env`, `.env.local`, SMTP authorization code, or LLM key is committed.
- `APP_AUTH_SECRET` is long and random.
- `APP_CORS_ALLOWED_ORIGINS` contains the final Vercel domain, or `https://*.vercel.app` if you use Vercel preview deployments.
- SMTP passwords are authorization codes, not mailbox login passwords.
