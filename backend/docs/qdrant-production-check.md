# Qdrant Production Check

Use this checklist when `VECTOR_STORE_ENABLED=true`.

## Start Local Qdrant

```powershell
docker compose -f docker-compose.qdrant.yml up -d
.\tools\verify-qdrant.ps1 -BaseUrl http://127.0.0.1:6333 -Collection learning_assistant_chunks
```

## Backend Configuration

```env
VECTOR_STORE_ENABLED=true
VECTOR_STORE_PROVIDER=qdrant
VECTOR_STORE_BASE_URL=http://127.0.0.1:6333
VECTOR_STORE_COLLECTION=learning_assistant_chunks
```

The backend creates the collection on first embedding upsert. Retrieval uses Qdrant first and falls back to MySQL embedding scan when Qdrant is disabled, unreachable, or empty.

## Verification Gates

1. Upload or reparse a material with `EMBEDDING_ENABLED=true`.
2. Confirm Qdrant contains points:

```powershell
Invoke-RestMethod http://127.0.0.1:6333/collections/learning_assistant_chunks
```

3. Ask a material question and confirm sources are returned.
4. Run backend tests:

```powershell
.\mvnw.cmd test
```
