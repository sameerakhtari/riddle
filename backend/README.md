# Riddle private oracle backend

The backend protects the AI-provider key. The Android app sends a rendered PNG
and a small recent-memory catalog; this service calls an OpenAI-compatible
vision endpoint and returns a short reply plus a transcription.

## Docker deployment

```bash
cd backend
cp .env.example .env
nano .env
docker compose up -d --build
curl http://127.0.0.1:8787/health
```

On the phone, set the backend URL to the LAN address of the Docker host, for
example `http://192.168.100.43:8787`, and enter the same `RIDDLE_APP_TOKEN`.

## Security

- Never commit the real `.env` file.
- Do not expose port 8787 directly to the public internet.
- For remote access, place it behind HTTPS, authentication and rate limiting.
- The provider key remains on the backend and is never compiled into the APK.
- The service validates PNG signatures and rejects oversized pages.
