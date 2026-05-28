# AGENTS.md

This file provides guidance to Codex and other coding agents when working in this repository.

## Project Overview

PaiSmart (派聪明) is an enterprise AI knowledge management system built around RAG. The backend ingests documents, parses and chunks them, generates DashScope embeddings, writes searchable records into Elasticsearch, and answers user questions by streaming DeepSeek responses over WebSocket. The frontend is a Vue 3 admin-style application with upload, knowledge-base, chat, auth, route, and theme modules.

The most important system boundary is the RAG pipeline:

1. Upload and deduplicate files.
2. Parse files into chunks.
3. Vectorize chunks through DashScope.
4. Store metadata in MySQL and searchable vectors/text in Elasticsearch.
5. Search with permission filtering.
6. Stream a cited answer through the chat WebSocket.
7. Persist recent conversation state in Redis.

## Development Commands

### Backend

Spring Boot 3.4.2, Java 17, Maven:

```bash
mvn spring-boot:run
mvn spring-boot:run -Dspring-boot.run.profiles=dev
mvn clean package
mvn test
mvn test -Dtest=UserServiceTest
```

### Frontend

Vue 3, TypeScript, pnpm:

```bash
cd frontend
pnpm install
pnpm dev
pnpm build
pnpm typecheck
pnpm lint
pnpm preview
```

Frontend dev server runs on port `9527`; preview runs on `9725`.

### Infrastructure

```bash
cd docs
docker-compose up -d
```

The compose stack is expected to provide MySQL, Redis, MinIO, Kafka, and Elasticsearch.

## Backend Structure

Root package: `com.yizhaoqi.smartpai`.

```text
client/       External AI clients, including DeepSeekClient and EmbeddingClient
config/       Spring Security, JWT, WebSocket, Kafka, ES, MinIO, Redis config
consumer/     Kafka file-processing consumers
controller/   REST endpoints for auth, users, upload, documents, parse, chat, search, admin
entity/       DTOs and value objects such as EsDocument, Message, SearchRequest, TextChunk
handler/      WebSocket handlers, especially ChatWebSocketHandler
model/        JPA entities such as User, FileUpload, ChunkInfo, Conversation, DocumentVector
repository/   Spring Data repositories and RedisRepository
service/      Business services
exception/    Custom exceptions
utils/        JWT, logging, password, and MinIO migration helpers
```

## Key Backend Services

| Service | Responsibility |
|---|---|
| `ChatHandler` | Orchestrates chat RAG: conversation history, search, DeepSeek streaming, Redis history |
| `HybridSearchService` | Combines vector recall, keyword matching, BM25 rescore, and permission filters |
| `VectorizationService` | Chunks text, calls `EmbeddingClient`, writes vectors into Elasticsearch |
| `ParseService` | Parses files with Apache Tika, chunks text, persists `ChunkInfo` rows |
| `UploadService` | Handles chunked upload, MD5 deduplication, MinIO storage, Kafka task publishing |
| `ElasticsearchService` | Low-level Elasticsearch index and document operations |
| `OrgTagCacheService` | Resolves and caches user organization-tag visibility in Redis |
| `TokenCacheService` | Handles JWT blacklist and refresh-token cache behavior |

## File Processing Pipeline

1. Frontend uploads chunks through `POST /api/v1/upload/chunk`.
2. `UploadService` performs MD5 deduplication and assembles or stores the uploaded file in MinIO.
3. When the final chunk arrives, `UploadService` publishes a `FileProcessingTask` to Kafka topic `file-processing-topic1`.
4. `FileProcessingConsumer` downloads the file from MinIO or URL.
5. The consumer calls `ParseService.parseAndSave()` to extract text and save chunk metadata.
6. The consumer calls `VectorizationService.vectorize()` to embed chunks and index them into Elasticsearch.
7. Failed processing tasks should flow to DLT topic `file-processing-dlt` after retries.

When changing upload, parse, or vectorization code, verify the whole chain instead of only one service. A local unit test can prove a parser branch, but the real behavior also depends on Kafka, MinIO, MySQL, and Elasticsearch mappings.

## RAG Chat Flow

WebSocket entrypoint: `/chat/{jwtToken}`. In frontend development this is proxied through `/proxy-ws/chat/{token}` to `ws://localhost:8081`.

1. Client connects with the JWT token in the path.
2. Backend sends a connection message like `{"type":"connection","sessionId":"..."}`.
3. Frontend stores the `sessionId`; citation click-through depends on it.
4. User sends plain text.
5. `ChatHandler.processMessage()` retrieves or creates the Redis current conversation key: `user:{username}:current_conversation`.
6. It loads recent history from `conversation:{conversationId}`.
7. It calls `HybridSearchService.searchWithPermission()` and normally takes the top 5 results.
8. It builds cited context in the form `[N] (filename | MD5:hash) snippet...`.
9. It streams DeepSeek output as `{"chunk":"..."}` messages.
10. It sends a completion message such as `{"type":"completion","status":"finished",...}`.
11. It updates Redis conversation history with a 7-day TTL.

The stop button sends a payload containing:

```json
{"type":"stop","_internal_cmd_token":"WSS_STOP_CMD_..."}
```

The backend should treat this as an internal stop command and set the streaming stop flag.

## Search And Permissions

Main Elasticsearch index: `knowledge_base`.

Important indexed fields include:

- `textContent`
- `vector` with 2048 dimensions
- `userId`
- `orgTag`
- `public`
- `fileMd5`
- `chunkId`

The permission model is central. A user can access a document when at least one condition is true:

- the document `userId` matches the current user's database ID
- the document is public
- the document `orgTag` is in the user's effective organization tags

`OrganizationTag` supports a parent-child hierarchy. `OrgTagCacheService` resolves the user's effective tag set, including ancestors where applicable. Any search or document-listing change must preserve this three-way permission filter.

Hybrid search is expected to use vector recall, keyword matching, and BM25 rescore. The documented weighting is `queryWeight = 0.2` and `rescoreWeight = 1.0`. If embedding fails, the search path should degrade to text-only behavior instead of breaking chat completely.

## Configuration Notes

Primary backend config lives in `src/main/resources/application.yml`.

Important defaults:

- Backend port: `8081`
- API base path: `/api/v1`
- Database: MySQL 8 database `PaiSmart`
- JPA DDL mode: update
- Kafka topic: `file-processing-topic1`
- Kafka DLT topic: `file-processing-dlt`
- File limits: 50 MB per file, 100 MB per request
- Default chunk size: 512 characters
- DeepSeek base URL: `https://api.deepseek.com/v1`
- DeepSeek model: `deepseek-chat`
- DeepSeek temperature: `0.3`
- DeepSeek max tokens: `2000`
- Embedding provider: DashScope `text-embedding-v4`
- Embedding dimensions: `2048`
- Embedding batch size: `10`
- Elasticsearch index: `knowledge_base`
- Default admin username: `admin`; password comes from `PAISMART_ADMIN_PASSWORD`
- System prompt requires Simplified Chinese, conclusion-first answers, and citations like `(来源#N: filename)`

Frontend environment files:

- `frontend/.env` contains shared flags and response-code behavior.
- `frontend/.env.test` points to `http://localhost:8081/api/v1`.
- `frontend/.env.prod` contains the production backend URL.
- Dev WebSocket proxy path is `/proxy-ws`.

## Frontend Structure

```text
frontend/src/
service/api/       API functions such as auth, org-tag, route, and index APIs
service/request/   Axios instance, token injection, refresh, logout behavior
store/modules/     Pinia modules for auth, chat, route, theme, and tabs
views/             Page views, including chat, knowledge-base, and login
components/        Shared Vue components
layouts/           Page layouts
locales/           English and Chinese i18n resources
router/            Vue Router 4 and navigation guards
```

`frontend/vite.config.ts` defines:

- dev port `9527`
- preview port `9725`
- alias `@` to `src/`
- alias `~` to the frontend project root
- global SCSS import `@/styles/scss/global.scss`
- HTTP proxy when `VITE_HTTP_PROXY=Y`

## Frontend Request Layer

`frontend/src/service/request/index.ts` creates the Axios request instance. It attaches:

```text
Authorization: Bearer <token>
```

Backend response codes are interpreted through environment-configured lists:

- success code: `200`
- logout codes: `8888,8889`
- modal logout codes: `7777,7778`
- token refresh codes: `9999,9998,3333`

HTTP `403` should reset the auth store. When changing auth or request behavior, check silent logout, modal logout, token refresh, retry, and duplicate error reporting paths.

## Frontend Chat Store

The chat store uses:

```ts
useWebSocket(`/proxy-ws/chat/${store.token}`, { autoReconnect: true })
```

It captures `sessionId` from the first `connection` message. That session ID is used to correlate reference click-through via `getReferenceMd5(sessionId, refNumber)`.

When changing chat, verify both message streaming and citation lookup. A response can look correct while references fail if the session ID or reference numbering contract changes.

## Common Change Patterns

Backend feature path:

```text
model -> repository -> service -> controller
```

Frontend feature path:

```text
service/api -> Pinia store action -> Vue component -> router registration
```

New LLM or embedding provider:

1. Add a client under `client/`, mirroring the shape of `DeepSeekClient` or `EmbeddingClient`.
2. Add configuration properties in `application.yml`.
3. Swap or select the provider through Spring configuration.
4. Preserve failure handling so chat/search can degrade cleanly when an external provider is unavailable.

## Testing Guidance

Backend tests live under `src/test/java/com/yizhaoqi/smartpai/service/`.

Known tests include:

- `UserServiceTest`
- `ConversationServiceTest`
- `ParseServiceTest`
- `ParseServiceUnitTest`
- `UploadServicePerformanceTest`
- `JwtUtilsRefreshTest`

Run a targeted backend test with:

```bash
mvn test -Dtest=ParseServiceUnitTest
```

Frontend checks should prefer:

```bash
cd frontend
pnpm typecheck
pnpm lint
pnpm build
```

Use focused tests for narrow edits, but broaden verification when touching shared auth, request handling, WebSocket chat, search permissions, upload processing, or external provider integration.

## Agent Working Rules

- Read existing code before changing behavior; this project has cross-service contracts between frontend, Redis, Kafka, Elasticsearch, and MySQL.
- Do not remove permission filters from search or document APIs.
- Do not change indexed field names or vector dimensions casually; Elasticsearch mappings and DashScope embeddings depend on them.
- Do not break the WebSocket message contract for `connection`, streamed `chunk`, `completion`, and `stop` messages.
- Preserve Simplified Chinese answer behavior in the AI system prompt unless explicitly asked otherwise.
- Treat external AI provider failures separately from local pipeline bugs. Provider outage or quota failure does not prove the upload, parse, vectorization, or chat orchestration code is wrong.
- Keep frontend route, store, and API changes aligned; most visible features require all three.
- Prefer small, verifiable changes. For RAG behavior, cite the exact service, key, topic, index, or endpoint affected.
