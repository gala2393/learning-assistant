# Backend Deployment

1. Install Java 21+ and MySQL 8.x on the server.
2. Copy `.env.example` to `.env.local` and fill in real values.
3. Set `APP_CORS_ALLOWED_ORIGINS` to the frontend origin, for example `https://example.com`.
4. Start with:

```bash
chmod +x start-backend.sh
./start-backend.sh
```

For production, prefer running the packaged jar under systemd or another process manager.
Uploaded materials are stored under `APP_STORAGE_DIR`; database records store paths relative to that directory.
