# AGENTS.md

This file provides guidance to Codex and other coding agents when working in this repository.

## Project Overview

PaiSmart is a distributed SCA software security vulnerability detection platform. The intended product direction is high-concurrency scanning for 100k+ open-source components plus LLM-assisted vulnerability remediation Q&A. The system is also used as a research and implementation ground for microservice high-availability governance, including rate limiting, degradation, transactional consistency, and RAG/Agentic RAG answer pipelines.

The current codebase still contains a general enterprise knowledge-base UI and document-ingestion pipeline, but agents should interpret RAG work through the SCA domain: component inventories, SBOMs, CVEs, vulnerable version ranges, fixed versions, remediation plans, license/security policies, scan reports, and historical remediation evidence.

The backend ingests documents, parses and chunks them, generates DashScope embeddings, writes searchable records into Elasticsearch, and answers user questions by streaming DeepSeek responses over WebSocket. The frontend is a Vue 3 admin-style application with upload, knowledge-base, chat, auth, route, and theme modules.

The most important system boundary is the RAG pipeline:

1. Upload and deduplicate files.
2. Parse files into chunks.
3. Vectorize chunks through DashScope.
4. Optionally extract SCA graph nodes/edges such as components, versions, CVEs, projects, and dependency/remediation relationships.
5. Store metadata in MySQL and searchable vectors/text in Elasticsearch.
6. Search with permission filtering.
7. Run a bounded Agentic RAG orchestration layer with optional graph evidence.
8. Stream a cited answer through the chat WebSocket.
9. Persist recent conversation state in Redis.

For SCA remediation Q&A, the target Agentic RAG flow should answer questions such as:

- Whether a component/version is affected by a CVE.
- Which fixed version or mitigation path is available.
- Whether the dependency is direct or transitive and how to locate the import path.
- Whether a finding may be a false positive based on project context.
- What remediation guidance should be shown to developers, SecOps, or managers.

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

On this local Windows machine, Maven dependencies should stay on D drive:

```powershell
& "C:\Users\Administrator\tools\apache-maven-3.9.10\bin\mvn.cmd" "-Dmaven.repo.local=D:\PaiSmart-deps\maven-repository" test
```

### Git Hooks

Repository hooks live in `.githooks`. Enable them once per local clone:

```powershell
.\scripts\setup-git-hooks.ps1
```

The current `pre-push` hook runs targeted Agentic RAG graph tests when related Java or backend config files changed:

```powershell
mvn test -Dtest=AgenticRagServiceTest,GraphSearchServiceTest,GraphExtractionServiceTest
```

Set `MAVEN_CMD` to override the Maven executable or `MAVEN_REPO_ARG` to override the local Maven repository argument.

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

On this local Windows machine, pnpm/npm caches should stay on D drive. Do not commit machine-specific cache paths in `frontend/.npmrc`.

### Infrastructure

```bash
cd docs
docker-compose up -d
```

The compose stack is expected to provide MySQL, Redis, MinIO, Kafka, and Elasticsearch.

Current local Docker defaults used during verification:

- MySQL: `localhost:3306`, root password `changeme`, database `paismart`
- Redis: `localhost:6379`, password `changeme`
- Kafka: `localhost:9092`
- MinIO: `localhost:19000`, user `admin`, password `changeme`, bucket `uploads`
- Elasticsearch: `localhost:9200`, user `elastic`, password `changeme`, scheme `http`

## Backend Structure

Root package: `com.yizhaoqi.smartpai`.

```text
client/       External AI clients, including DeepSeekClient and EmbeddingClient
config/       Spring Security, JWT, WebSocket, Kafka, ES, MinIO, Redis config
consumer/     Kafka file-processing consumers
controller/   REST endpoints for auth, users, upload, documents, parse, chat, search, admin
entity/       DTOs and value objects, including Agentic RAG request/result/trace DTOs
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
| `ChatHandler` | Orchestrates chat RAG: conversation history, Agentic RAG, DeepSeek streaming, Redis history, reference mapping |
| `ChatIntentGuard` | Short-circuits small-talk/no-op chat inputs before Agentic RAG while forcing SCA-signal inputs to keep the RAG path |
| `AgenticRagService` | Bounded Agentic RAG state machine: rule entity merge, planning, permissioned search, evidence evaluation, optional rewrite/research, context budgeting |
| `HybridSearchService` | Combines vector recall, keyword matching, BM25 rescore, and permission filters |
| `DeepSeekClient` | Streams final answers and provides non-streaming JSON completion for planner/evaluator steps |
| `VectorizationService` | Chunks text, calls `EmbeddingClient`, writes vectors into Elasticsearch |
| `ParseService` | Parses files with Apache Tika, chunks text, persists `ChunkInfo` rows |
| `UploadService` | Handles chunked upload, MD5 deduplication, MinIO storage, Kafka task publishing |
| `ElasticsearchService` | Low-level Elasticsearch index and document operations |
| `ScaEntityExtractionService` | Extracts deterministic SCA entities from user questions before planner execution |
| `GraphExtractionService` | Extracts SCA graph candidates from chunks with rule patterns plus optional DeepSeek JSON extraction |
| `GraphSearchService` | Finds permission-filtered graph paths for Agentic RAG entity evidence |
| `RagEvalService` | Runs enabled database-backed RAG eval cases through `AgenticRagService` and persists run/case results |
| `OrgTagCacheService` | Resolves and caches user organization-tag visibility in Redis |
| `TokenCacheService` | Handles JWT blacklist and refresh-token cache behavior |

## File Processing Pipeline

1. Frontend uploads chunks through `POST /api/v1/upload/chunk`.
2. `UploadService` performs MD5 deduplication and assembles or stores the uploaded file in MinIO.
3. When the final chunk arrives, `UploadService` publishes a `FileProcessingTask` to Kafka topic `file-processing-topic1`.
4. `FileProcessingConsumer` downloads the file from MinIO or URL.
5. The consumer calls `ParseService.parseAndSave()` to extract text and save chunk metadata.
6. The consumer calls `VectorizationService.vectorize()` to embed chunks and index them into Elasticsearch.
7. If enabled, vectorization invokes `GraphExtractionService` to refresh `graph_node` and `graph_edge` rows for the file.
8. Failed processing tasks should flow to DLT topic `file-processing-dlt` after retries.

When changing upload, parse, or vectorization code, verify the whole chain instead of only one service. A local unit test can prove a parser branch, but the real behavior also depends on Kafka, MinIO, MySQL, and Elasticsearch mappings.

## Agentic RAG Chat Flow

WebSocket entrypoint: `/chat/{jwtToken}`. In frontend development this is proxied through `/proxy-ws/chat/{token}` to `ws://localhost:8081`.

1. Client connects with the JWT token in the path.
2. Backend sends a connection message like `{"type":"connection","sessionId":"..."}`.
3. Frontend stores the `sessionId`; citation click-through depends on it.
4. User sends plain text.
5. `ChatHandler.processMessage()` retrieves or creates the Redis current conversation key: `user:{username}:current_conversation`.
6. It loads recent history from `conversation:{conversationId}`.
7. `ChatIntentGuard` routes short small-talk/no-op inputs such as `你好`, `谢谢`, `你是谁`, or pure punctuation to a fixed Chinese response. This short-circuit still sends `{"chunk":"..."}`, then `completion`, updates Redis history, and clears reference mappings, but it must not call `AgenticRagService` or DeepSeek streaming.
8. If the message contains SCA signals such as CVE IDs, components, versions, SBOM, vulnerabilities, fixes, dependencies, licenses, or false-positive terms, `ChatIntentGuard` must force the normal RAG path even when the message begins with a greeting.
9. It calls `AgenticRagService.run()` with `userId`, message, history, and `sessionId`.
10. `AgenticRagService` first extracts deterministic SCA entities from the user question, then executes a fixed, bounded state machine: `PLAN_QUERY -> SEARCH -> EVALUATE_EVIDENCE -> OPTIONAL_REWRITE_AND_RESEARCH -> FINAL_CONTEXT`.
11. Planner/evaluator steps use `DeepSeekClient.completeJson(...)`. Planner entities are merged with rule entities, with rule entities kept first. JSON failures, timeouts, or planner failures must degrade to ordinary RAG instead of breaking chat.
12. All retrieval must continue to call `HybridSearchService.searchWithPermission()`. Never bypass the owner/public/org-tag permission filter.
13. When graph search is enabled and merged entities are non-empty, `AgenticRagService` calls `GraphSearchService.searchWithPermission()` and adds graph-path evidence without replacing text evidence. Planner fallback may still add graph evidence when rule entities were extracted.
14. `AgenticRagService` merges, deduplicates, ranks, and truncates evidence within the configured context budget.
15. It builds cited context in the form `[N] (filename | MD5:hash) snippet...` and returns `referenceMapping`.
16. `ChatHandler` saves `sessionId -> referenceNumber -> fileMd5`.
17. It streams DeepSeek output as `{"chunk":"..."}` messages.
18. It sends a completion message such as `{"type":"completion","status":"finished",...}`.
19. It updates Redis conversation history with a 7-day TTL.

Citation click-through depends on `/api/v1/documents/reference-md5?sessionId=...&referenceNumber=N`. A response can look correct while references fail if the session ID or reference numbering contract changes.

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

Graph evidence is stored in MySQL tables `graph_node` and `graph_edge`. Graph rows carry `userId`, `orgTag`, `isPublic`, `fileMd5`, `chunkId`, and `ingestionTraceId`; graph search must preserve the same owner/public/org-tag permission model as document search.

The permission model is central. A user can access a document when at least one condition is true:

- the document `userId` matches the current user's database ID
- the document is public
- the document `orgTag` is in the user's effective organization tags

`OrganizationTag` supports a parent-child hierarchy. `OrgTagCacheService` resolves the user's effective tag set, including ancestors where applicable. Any search or document-listing change must preserve this three-way permission filter.

Permissioned hybrid search uses two independent recall branches: vector KNN recall and BM25 text recall. `HybridSearchService.searchWithPermission()` merges the two ranked lists with Reciprocal Rank Fusion (RRF), deduplicating by `fileMd5 + chunkId`, and returns the requested `topK`. The vector branch must not require `textContent` keyword matches; both branches must preserve the same owner/public/org-tag permission filter. If embedding fails, the search path should degrade to text-only behavior instead of breaking chat completely.

## Configuration Notes

Primary backend config lives in `src/main/resources/application.yml`.

Important defaults:

- Backend port: `8081`
- API base path: `/api/v1`
- Database: MySQL 8 database `PaiSmart` / local Docker database `paismart`
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
- System prompt requires Simplified Chinese, conclusion-first answers, and citations like `(source#N: filename)` / `(来源#N: filename)`
- Agentic RAG can be toggled with `ai.agentic.enabled` / `PAISMART_AGENTIC_RAG_ENABLED`
- Agentic RAG default limits: max search rounds `2`, first round topK `12`, rewrite round topK `8`, max subqueries `4`, max context chars `1600`
- Agentic graph defaults: enabled `true`, extraction enabled `true`, max depth `3`, topK `8`

Do not commit real API keys. Local machine secrets should stay in ignored files such as `src/main/resources/application-local.yml`, or in environment variables. `application-local.yml` is intentionally ignored by `.gitignore`.

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

## Observability And Traceability

Current logs are useful for local troubleshooting but are not yet full production-grade distributed tracing.

Existing correlation anchors:

- HTTP requests get a short `requestId` through `LoggingInterceptor`.
- WebSocket chat uses `sessionId`.
- Upload, parse, vectorization, and search can usually be correlated by `fileMd5`.
- User operations commonly include `userId` or username.
- Agentic RAG internally returns `AgentTraceStep` objects with stage, duration, input summary, output summary, and failure reason.
- Agentic RAG emits `agentic_rag_trace` INFO logs with `traceId`, `sessionId`, `userId`, `stage`, `durationMs`, `inputSummary`, `outputSummary`, and `failureReason`.
- Graph extraction emits `graph_extraction_audit` / `graph_extraction_failed`; graph retrieval emits `GRAPH_SEARCH` trace steps and optional `agentic_rag_graph_search` INFO logs when LLM-decision logging is enabled.

Main log files:

```text
logs/smartpai.YYYY-MM-DD.log
logs/business.YYYY-MM-DD.log
logs/error.YYYY-MM-DD.log
logs/performance.YYYY-MM-DD.log
```

Useful local checks:

```bash
rg "sessionId=<id>|Start chat processing|Reference mapping|DeepSeek|searchWithPermission|completion" logs
rg "agentic_rag_trace traceId=<id>" logs
rg "fileMd5=<md5>|MERGE_FILE|Kafka|PARSE|vector|knowledge_base" logs
rg "reference-md5|GET_REFERENCE_MD5|Reference mapping" logs
```

Known traceability gaps:

- No single `traceId` currently spans upload, Kafka, parse, vectorization, ES indexing, chat, Agentic RAG, LLM generation, and citation lookup.
- WebSocket logging is not fully MDC-correlated in the same way as normal HTTP requests.
- Agentic trace logs are emitted as text logs, not structured JSON logs.
- Logs are text-oriented, not JSON logs designed for ELK/Loki/OpenSearch ingestion.

If adding observability, prefer a `traceId` propagated through MDC, WebSocket session attributes, Kafka headers, Agentic RAG trace events, and citation lookup.

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

Backend tests live under `src/test/java/com/yizhaoqi/smartpai/`.

Known tests include:

- `AgenticRagServiceTest`
- `ScaEntityExtractionServiceTest`
- `RagEvalServiceTest`
- `GraphExtractionServiceTest`
- `GraphSearchServiceTest`
- `UserServiceTest`
- `ConversationServiceTest`
- `ParseServiceTest`
- `ParseServiceUnitTest`
- `UploadServicePerformanceTest`
- `JwtUtilsRefreshTest`

Run targeted backend tests with:

```bash
mvn test -Dtest=ParseServiceUnitTest
mvn test -Dtest=AgenticRagServiceTest
mvn test -Dtest=ScaEntityExtractionServiceTest,AgenticRagServiceTest,RagEvalServiceTest,GraphSearchServiceTest
```

Run all backend tests with local Docker settings:

```powershell
$env:PAISMART_DB_PASSWORD='changeme'
$env:SPRING_DATA_REDIS_PASSWORD='changeme'
$env:PAISMART_ES_SCHEME='http'
$env:PAISMART_ES_PASSWORD='changeme'
$env:PAISMART_MINIO_ENDPOINT='http://localhost:19000'
$env:PAISMART_MINIO_PUBLIC_URL='http://localhost:19000'
$env:PAISMART_MINIO_ACCESS_KEY='admin'
$env:PAISMART_MINIO_SECRET_KEY='changeme'
$env:PAISMART_ADMIN_PASSWORD='changeme'
$env:PAISMART_AGENTIC_RAG_ENABLED='true'
& "C:\Users\Administrator\tools\apache-maven-3.9.10\bin\mvn.cmd" "-Dmaven.repo.local=D:\PaiSmart-deps\maven-repository" test
```

There is currently no fully automated Agentic RAG integration test in `mvn test`. The existing Agentic RAG coverage is primarily unit-level with mocked DeepSeek/search collaborators. Full end-to-end verification has been done manually with local Docker infrastructure:

```text
upload -> Kafka -> parse -> vectorize -> Elasticsearch -> searchWithPermission -> WebSocket -> Agentic RAG -> DeepSeek stream -> reference-md5
```

Recommended future integration tests:

- `AgenticRagIntegrationTest`: Spring context, mocked LLM planner/evaluator, real `HybridSearchService`, permissioned search assertions.
- `ChatWebSocketIntegrationTest`: random-port Spring Boot, WebSocket client, `connection/chunk/completion`, and `/documents/reference-md5`.
- Docker-backed `*IT.java` tests through Maven Failsafe for MySQL, Redis, Kafka, MinIO, and Elasticsearch dependent flows.

Frontend checks should prefer:

```bash
cd frontend
pnpm typecheck
pnpm lint
pnpm build
```

Use focused tests for narrow edits, but broaden verification when touching shared auth, request handling, WebSocket chat, search permissions, upload processing, external provider integration, or SCA remediation behavior.

## Agent Working Rules

- Read existing code before changing behavior; this project has cross-service contracts between frontend, Redis, Kafka, Elasticsearch, and MySQL.
- Treat this as an SCA vulnerability detection and remediation platform, not only a generic document Q&A app.
- Do not remove permission filters from search or document APIs.
- Do not change indexed field names or vector dimensions casually; Elasticsearch mappings and DashScope embeddings depend on them.
- Do not break the WebSocket message contract for `connection`, streamed `chunk`, `completion`, and `stop` messages.
- Do not break citation lookup through `sessionId -> referenceNumber -> fileMd5`.
- Preserve Simplified Chinese answer behavior in the AI system prompt unless explicitly asked otherwise.
- Treat external AI provider failures separately from local pipeline bugs. Provider outage or quota failure does not prove the upload, parse, vectorization, or chat orchestration code is wrong.
- Keep frontend route, store, and API changes aligned; most visible features require all three.
- Prefer small, verifiable changes. For RAG behavior, cite the exact service, key, topic, index, or endpoint affected.
- Never commit local API keys, tokens, passwords, or machine-specific dependency cache paths.
