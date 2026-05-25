# Learning Assistant

This folder keeps the backend and frontend together while preserving their separate runtimes.

## Local start

Backend:

```bat
cd /d C:\Users\23931\Desktop\learning-assistant\backend
start-backend.cmd
```

Frontend:

```bat
cd /d C:\Users\23931\Desktop\learning-assistant\frontend
start-frontend.cmd
```

The `.cmd` files call the PowerShell scripts with `ExecutionPolicy Bypass`, so they work even when direct `.ps1` execution is blocked.

## Upload package

Create a clean upload package from the project root:

```bat
cd /d C:\Users\23931\Desktop\learning-assistant
powershell -NoProfile -ExecutionPolicy Bypass -File .\prepare-upload.ps1
```

The generated `upload-package` excludes local secrets, dependency caches, build output, logs, and local uploaded files. On the server, create `backend\.env.local` from `backend\.env.example` and fill in the real database, model, OCR, and CORS settings.
