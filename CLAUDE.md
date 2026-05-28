wsm# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

PaiSmart (派聪明) is an enterprise-grade AI knowledge management system built with RAG (Retrieval-Augmented Generation). The system ingests documents, chunks and vectorizes them via DashScope, indexes into Elasticsearch, and answers user questions by streaming responses from DeepSeek through a WebSocket connection.

## Development Commands

### Backend (Spring Boot 3.4.2 / Java 17 / Maven)
```bash
mvn spring-boot:run                                    # default profile
mvn spring-boot:run -Dspring-boot.run.profiles=dev     # dev profile
mvn clean package                                      # build JAR
mvn test                                               # all tests
mvn test -Dtest=UserServiceTest                        # single test class
```

### Frontend (Vue 3 / TypeScript / pnpm)
```bash
cd frontend
pnpm install          # install dependencies
pnpm dev              # dev server on :9527
pnpm build            # production build
pnpm typecheck        # tsc type check
pnpm lint             # ESLint
pnpm preview          # preview build on :9725
```

### Infrastructure
```bash
cd docs && docker-compose up -d   # starts MySQL, Redis, MinIO, Kafka, Elasticsearch
```

## Architecture

### Backend Package Layout
```
com.yizhaoqi.smartpai/
├── client/           DeepSeekClient, EmbeddingClient (external AI API callers)
├── config/           Spring Security, JWT filter, WebSocket, Kafka, ES, MinIO, Redis
├── consumer/         FileProcessingConsumer (Kafka listener → parse → vectorize)
├── controller/       REST endpoints (Auth, User, Upload, Document, Parse, Chat,
│                     Conversation, Search, Admin)
├── entity/           DTO/value objects (EsDocument, Message, SearchRequest/Result, TextChunk)
├── handler/          ChatWebSocketHandler (upgrades WS, delegates to ChatHandler)
├── model/            JPA entities (User, FileUpload, ChunkInfo, Conversation,
│                     DocumentVector, OrganizationTag, FileProcessingTask)
├── repository/       Spring Data JPA repos + RedisRepository
├── service/          Business logic (see below)
├── exception/        CustomException, InvalidTokenException
└── utils/            JwtUtils, LogUtils, PasswordUtil, MinioMigrationUtil
```

### Key Services
| Service | Responsibility |
|---|---|
| `ChatHandler` | Orchestrates RAG: history → search → DeepSeek stream → save to Redis |
| `HybridSearchService` | KNN vector + BM25 rescore on `knowledge_base` index with permission filtering |
| `VectorizationService` | Chunks text → calls EmbeddingClient → indexes into Elasticsearch |
| `ParseService` | Apache Tika parsing, 512-char chunking, saves `ChunkInfo` to MySQL |
| `UploadService` | Chunked upload to MinIO, MD5 dedup, publishes to Kafka |
| `ElasticsearchService` | Low-level ES CRUD for the `knowledge_base` index |
| `OrgTagCacheService` | Caches user ↔ org-tag hierarchy in Redis |
| `TokenCacheService` | JWT token blacklisting / refresh caching |

### File Processing Pipeline
1. Frontend uploads chunk(s) via `POST /api/v1/upload/chunk` (MD5-based dedup)
2. On final chunk, `UploadService` publishes `FileProcessingTask` to Kafka topic **`file-processing-topic1`**
3. `FileProcessingConsumer` downloads the file from MinIO/URL, calls `ParseService.parseAndSave()` then `VectorizationService.vectorize()`
4. Failed tasks go to DLT topic **`file-processing-dlt`** after retries

### RAG Chat Flow
1. Frontend connects via WebSocket at **`/chat/{jwtToken}`** (proxied as `/proxy-ws/chat/{token}`)
2. On connect, backend sends `{"type":"connection","sessionId":"..."}` — frontend stores `sessionId`
3. User sends plain text; `ChatHandler.processMessage()`:
   - Retrieves/creates conversation ID from Redis key `user:{username}:current_conversation`
   - Loads last 20 messages from Redis key `conversation:{conversationId}`
   - Calls `HybridSearchService.searchWithPermission()` (top 5 results)
   - Builds context with citation format: `[N] (filename | MD5:hash) snippet…`
   - Streams DeepSeek response back as `{"chunk":"..."}` messages
   - Sends `{"type":"completion","status":"finished",...}` when done
   - Updates Redis conversation history (7-day TTL)
4. Stop button sends `{"type":"stop","_internal_cmd_token":"WSS_STOP_CMD_..."}` — backend sets stop flag

### Hybrid Search
Index: **`knowledge_base`** in Elasticsearch. Each document has fields `textContent`, `vector` (2048-dim), `userId`, `orgTag`, `public`, `fileMd5`, `chunkId`.

Permission filter: a user can access documents where `userId == userDbId` OR `public == true` OR `orgTag IN userEffectiveTags`.

Strategy: KNN (vector) recall → bool must (keyword match) → BM25 rescore (queryWeight 0.2, rescoreWeight 1.0). Falls back to text-only on embedding failure.

### Multi-Tenancy
Each document carries `orgTag` (e.g. `default`) and `isPublic`. `OrganizationTag` has a parent-child hierarchy. `OrgTagCacheService` resolves a user's full set of effective tags (self + ancestors). All search and document queries apply the three-condition permission filter.

## Configuration

### Backend (`src/main/resources/application.yml`)
- Server port: **8081**
- API base: `/api/v1`
- DB: `PaiSmart` (MySQL 8, DDL auto-update)
- Kafka topic: `file-processing-topic1`, DLT: `file-processing-dlt`
- File limits: 50 MB per file, 100 MB per request, chunk size 512 chars
- DeepSeek: `https://api.deepseek.com/v1`, model `deepseek-chat`, temp 0.3, max-tokens 2000
- Embedding: DashScope `text-embedding-v4`, 2048 dims, batch 10
- ES index: `knowledge_base`, scheme `https`
- Default admin username: `admin`; password comes from `PAISMART_ADMIN_PASSWORD`
- AI system prompt enforces: Simplified Chinese only, conclusion-first, cite as `(来源#N: filename)`

### Frontend Environment Files
- `.env` — shared flags; success code `200`; logout codes `8888,8889`; modal-logout codes `7777,7778`; token-refresh codes `9999,9998,3333`; router mode `hash`
- `.env.test` — backend base URL `http://localhost:8081/api/v1`
- `.env.prod` — production backend URL
- WS proxy path `/proxy-ws` → `ws://localhost:8081`

### Frontend (`frontend/vite.config.ts`)
- Dev port: **9527**, preview port: **9725**
- Aliases: `@` → `src/`, `~` → project root
- SCSS global import: `@/styles/scss/global.scss`
- HTTP proxy enabled in dev when `VITE_HTTP_PROXY=Y`

## Frontend Structure

```
frontend/src/
├── service/api/       API call functions (auth.ts, org-tag.ts, route.ts, index.ts)
├── service/request/   Axios factory with auto token-refresh and error deduplication
├── store/modules/
│   ├── auth/          Login, token, user info (localStorage via VITE_STORAGE_PREFIX)
│   ├── chat/          WebSocket state, message list, sessionId capture
│   ├── route/         Dynamic/static route tree
│   ├── theme/         UI theming
│   └── tab/           Open tabs
├── views/             Page components (chat, knowledge-base, login)
├── components/        Shared components
├── layouts/           Page layouts
├── locales/           i18n (EN + ZH)
└── router/            Vue Router 4 with navigation guards
```

### Request Layer
`service/request/index.ts` creates a flat Axios instance. On every request it attaches `Authorization: Bearer <token>`. On backend failure it checks response code against env-configured code lists to decide: silent logout, modal logout, or transparent token refresh + retry. HTTP 403 always triggers `authStore.resetStore()`.

### Chat Store WebSocket
```ts
useWebSocket(`/proxy-ws/chat/${store.token}`, { autoReconnect: true })
```
Captures `sessionId` from the first `connection`-type message. Used to correlate citation click-through (`getReferenceMd5(sessionId, refNumber)`).

## Testing

Backend tests live in `src/test/java/com/yizhaoqi/smartpai/service/`:
- `UserServiceTest`, `ConversationServiceTest`, `ParseServiceTest` — Spring integration tests
- `ParseServiceUnitTest`, `UploadServicePerformanceTest` — unit/perf tests
- `JwtUtilsRefreshTest` — JWT utility tests

Run a single test: `mvn test -Dtest=ParseServiceUnitTest`

## Adding New Features

**Backend pattern:** model → repository → service → controller. JPA DDL is `update` so schema changes apply automatically on restart.

**Frontend pattern:** `service/api/` function → Pinia store action → Vue component → register route in `router/`.

**New LLM or embedding provider:** implement in `client/` mirroring `DeepSeekClient`/`EmbeddingClient`, swap bean in `application.yml`.
