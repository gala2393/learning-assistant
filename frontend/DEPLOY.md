# Frontend Deployment on Vercel

Use Vercel for the React/Vite frontend.

## Vercel project settings

- Root Directory: `frontend`
- Install Command: `npm install` or `npm ci`
- Build Command: `npm run build`
- Output Directory: `dist`

## Environment variables

Set this in Vercel:

```env
VITE_API_BASE=https://<your-railway-backend-domain>/api
```

The app reads `VITE_API_BASE` at build time. After changing it, redeploy the Vercel project.

If you use a Vercel preview or the default `*.vercel.app` domain, make sure Railway also allows that exact origin in `APP_CORS_ALLOWED_ORIGINS`.

## Routing

`vercel.json` keeps browser refreshes working for React Router by rewriting non-API paths to `index.html`.

API calls are made directly to `VITE_API_BASE`; the Railway backend must allow your Vercel domain in:

```env
APP_CORS_ALLOWED_ORIGINS=https://<your-vercel-domain>
```
