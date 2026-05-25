# Frontend Deployment

Build on the server or locally:

```bash
npm ci
npm run build
```

Deploy `dist/` with Nginx or another static server. If Nginx proxies `/api` to the backend, keep:

```env
VITE_API_BASE=/api
```

If frontend and backend use different domains, set `VITE_API_BASE` before build:

```env
VITE_API_BASE=https://api.example.com/api
```
