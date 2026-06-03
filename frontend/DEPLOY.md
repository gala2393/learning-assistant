# Frontend Deployment on Vercel

Use Vercel for the React/Vite frontend.

## Vercel project settings

- Root Directory: `frontend`
- Install Command: `npm install` or `npm ci`
- Build Command: `npm run build`
- Output Directory: `dist`

## Environment variables

Set one of these in Vercel:

```env
VITE_API_BASE=https://<your-railway-backend-domain>/api
```

or:

```env
BACKEND_API_BASE=https://<your-railway-backend-domain>/api
```

The app reads `VITE_API_BASE` at build time. After changing it, redeploy the Vercel project.

If `VITE_API_BASE` is missing, the app uses same-origin `/api`; the included Vercel API proxy then reads `BACKEND_API_BASE` and forwards `/api/*` to Railway. Without either variable, login requests will hit Vercel itself and return 404.

If you use Vercel previews, Railway can allow them with an origin pattern:

```env
APP_CORS_ALLOWED_ORIGINS=https://<your-vercel-domain>,https://*.vercel.app
```

## Routing

`vercel.json` keeps browser refreshes working for React Router by rewriting non-API paths to `index.html`.

API calls are made directly to `VITE_API_BASE` when it is set, or through the Vercel `/api/*` proxy when `BACKEND_API_BASE` is set. The Railway backend must allow your Vercel domain in:

```env
APP_CORS_ALLOWED_ORIGINS=https://<your-vercel-domain>
```
