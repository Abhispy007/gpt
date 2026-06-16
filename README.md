# GPT LLM Shadow Proxy

Spring Boot app that synchronously serves customer traffic from a Primary LLM mock while asynchronously shadowing the same request to a Candidate LLM mock. If the parsed JSON outputs differ, the app logs and persists a redacted mismatch record.

This version keeps SQLite only for mismatch storage. Shadow work is queued separately through Redis Streams.

## Architecture

```mermaid
flowchart LR
  Client["Client POST /api/proxy"] --> Proxy["ProxyController"]
  Proxy --> Primary["Primary LLM"]
  Primary --> Proxy
  Proxy --> Response["Return Primary Response"]

  Proxy --> Queue["Redis Stream shadow queue"]
  Queue --> Worker["Scheduled queue drain"]
  Worker --> Async["@Async ShadowJobProcessor"]
  Async --> Executor["shadowTaskExecutor"]
  Executor --> Candidate["Candidate LLM @CircuitBreaker"]
  Candidate --> Compare["JSON Extract + Compare"]
  Compare -->|Mismatch| Store["SQLite mismatches"]
  Compare -->|Mismatch| Logs["Redacted Logs"]
  Compare -->|Failure| Retry["Redis retry zset"]
  Retry --> Queue
  Compare -->|Max Attempts| DLQ["Redis dead-letter stream"]
```

## API

```text
POST /api/proxy
GET  /api/mismatches
GET  /metrics
POST /auth/token
POST /mock/primary
POST /mock/candidate
GET  /actuator/health
GET  /v3/api-docs
GET  /swagger-ui/index.html
```

## Authentication (API key and JWT)

Protected endpoints accept **either**:

1. **API key** — header `X-API-Key: <API_KEY>` (existing behavior, unchanged for DigitalOcean)
2. **JWT Bearer token** — header `Authorization: Bearer <token>` (when `JWT_SECRET` is configured)

### Option A: API key only (current DigitalOcean default)

Set `API_KEY` in App Platform. Send the same value as `X-API-Key` on every request. No JWT setup required.

### Option B: Exchange API key for JWT

When both `API_KEY` and `JWT_SECRET` are set:

```bash
# 1) Exchange API key for JWT
curl -s -X POST https://your-app.ondigitalocean.app/auth/token \
  -H 'X-API-Key: your-api-key'

# Response:
# {"accessToken":"eyJ...","tokenType":"Bearer","expiresIn":3600}

# 2) Use Bearer token on protected endpoints
curl -s https://your-app.ondigitalocean.app/metrics \
  -H 'Authorization: Bearer eyJ...'
```

JWT tokens expire after `JWT_EXPIRATION_SECONDS` (default `3600`). Your existing `X-API-Key` clients keep working without changes.

## Swagger

```text
http://localhost:8080/swagger-ui/index.html
```

Swagger shows two authorization options at the top:

1. **Authorize → apiKey** — paste your `API_KEY` value (sent as `X-API-Key`)
2. **Authorize → bearerAuth** — paste a JWT from `POST /auth/token` (sent as `Authorization: Bearer ...`)

You only need one of them per request. For JWT in Swagger: call **POST /auth/token** first (with apiKey authorized), copy `accessToken`, then authorize **bearerAuth** with that token.

If only `API_KEY` is configured (no `JWT_SECRET`), use **apiKey** only — same as before.

## Example Request

```bash
curl -s http://localhost:8080/api/proxy \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: dev-secret' \
  -d '{
    "prompt": "Return customer tier",
    "input": {
      "customerId": "123"
    },
    "forceMismatch": true
  }'
```

Example Primary response:

```json
{
  "model": "primary",
  "output": {
    "customerId": "123",
    "tier": "gold",
    "answer": "Processed prompt: Return customer tier"
  }
}
```

Then inspect persisted mismatches:

```bash
curl -s http://localhost:8080/api/mismatches \
  -H 'X-API-Key: dev-secret'
```

And check the live shadow match rate:

```bash
curl -s http://localhost:8080/metrics \
  -H 'X-API-Key: dev-secret'
```

Example metrics response:

```json
{
  "totalComparisons": 2,
  "matches": 1,
  "mismatches": 1,
  "matchRatePercentage": 50.0,
  "updatedAt": "2026-06-16T18:30:00.000000Z"
}
```

## Shadow Test Controls

```json
{
  "prompt": "Return customer tier",
  "input": {
    "customerId": "123"
  },
  "forceMismatch": true,
  "forceCandidateError": false,
  "candidateDelayMs": 3000
}
```

- `forceMismatch`: Candidate returns a different tier.
- `forceCandidateError`: Candidate returns HTTP 500.
- `candidateDelayMs`: Candidate sleeps before responding.

## Queue Design

`/api/proxy` calls the Primary model synchronously. After Primary returns, it publishes a copied `ShadowComparisonJob` to Redis Streams and immediately returns the Primary response.

The queued job contains only:

- request id
- request body DTO
- Primary raw response
- creation time
- attempt count

It does not contain servlet request or response objects, so the background job does not depend on the original HTTP connection staying open.

## Client Disconnect Safety

The background request context survives client disconnects because the app copies all required data into a standalone `ShadowComparisonJob` before returning from `/api/proxy`.

The copied job contains:

- generated request id
- validated request DTO
- raw Primary response
- creation timestamp

The job does not contain:

- `HttpServletRequest`
- `HttpServletResponse`
- controller thread state
- request input stream
- client socket

After the Primary model responds, `ProxyController` creates the copied job and submits it to the queue:

```java
ShadowComparisonJob job = new ShadowComparisonJob(requestId, request, primaryRawResponse, Instant.now());
shadowComparisonService.submit(job);
```

`RedisShadowJobQueue` serializes that copied job into Redis Streams. Once Redis accepts the message, the Candidate comparison can run later on a background thread even if the caller has already disconnected.

This is tested by `shadowJobPersistsAndRunsEvenWhenClientDisconnectsBeforeReadingResponse`, which opens a raw socket, sends the full HTTP request, closes the socket before reading the response, and still verifies that a mismatch record is written.

Queue pieces:

- Main queue: Redis Stream, configured by `SHADOW_QUEUE_STREAM`
- Consumer group: `SHADOW_QUEUE_GROUP`
- Delayed retries: Redis sorted set, configured by `SHADOW_QUEUE_RETRY_ZSET`
- Dead letters: Redis Stream, configured by `SHADOW_QUEUE_DLQ`

SQLite is not used as a queue. The old `shadow_jobs` table and repository are intentionally removed.

## Spring Boot Design

The code uses Spring-managed production patterns instead of hand-rolled wiring:

- `@ConfigurationProperties` classes bind and validate app, queue, retry, SQLite, client, and executor settings.
- `@EnableAsync` plus `@Async("shadowTaskExecutor")` runs shadow comparisons outside the request thread.
- `SecurityFilterChain` owns stateless API-key protection for `/api/**`, `/mock/**`, and `/metrics`.
- `@CircuitBreaker(name = "candidate")` applies Resilience4j to the Candidate client through Spring AOP.
- `FilterRegistrationBean` disables servlet auto-registration for the API-key filter so it runs only in the Spring Security chain.

## Retry And Circuit Breaker

Candidate failures do not affect the Primary response. Failed shadow jobs retry through Redis:

```properties
shadow.retry.max-attempts=3
shadow.retry.backoff-ms=1000
```

Candidate calls are protected with a Resilience4j circuit breaker named `candidate`. The assignment-facing settings are:

```properties
shadow.circuit-breaker.failure-threshold=3
shadow.circuit-breaker.open-duration-ms=10000
```

Those values feed the Resilience4j instance:

```properties
resilience4j.circuitbreaker.instances.candidate.sliding-window-type=COUNT_BASED
resilience4j.circuitbreaker.instances.candidate.minimum-number-of-calls=3
resilience4j.circuitbreaker.instances.candidate.failure-rate-threshold=100
resilience4j.circuitbreaker.instances.candidate.wait-duration-in-open-state=10000ms
```

When Resilience4j rejects a Candidate call because the circuit is open, the job is moved to the Redis retry zset until the open period expires. After max attempts, the job is written to the Redis dead-letter stream.

## Mismatch Storage

Mismatches are stored in SQLite:

```text
GET /api/mismatches
```

The stored JSON is redacted before persistence. Sensitive keys are configured by:

```properties
shadow.redaction.sensitive-keys=customerId,email,phone,ssn,token,apiKey,password
```

The app still logs:

```text
event=llm_shadow_mismatch requestId=... primaryJson=... candidateJson=...
```

## Local Setup

Run everything with Docker Compose:

```bash
docker compose up --build
```

That starts:

- Redis on `localhost:6379`
- Spring Boot on `localhost:8080`
- SQLite mismatch file at `./data/llm-shadow-proxy.sqlite`

Run tests:

```bash
mvn test
```

The test suite uses `shadow.queue.backend=memory` so CI does not need Redis.

## Docker

Build:

```bash
docker build -t llm-shadow-proxy-gpt .
```

Run with an existing Redis:

```bash
docker run --rm -p 8080:8080 \
  -e API_KEY=dev-secret \
  -e REDIS_URL=redis://host.docker.internal:6379 \
  -e SQLITE_PATH=/app/data/llm-shadow-proxy.sqlite \
  -v "$PWD/data:/app/data" \
  llm-shadow-proxy-gpt
```

## Configuration

Useful environment variables:

```text
PORT=8080
API_KEY=your-secret
JWT_SECRET=your-jwt-signing-secret-at-least-32-chars
JWT_EXPIRATION_SECONDS=3600
JWT_ISSUER=llm-shadow-proxy
REDIS_URL=redis://localhost:6379
SQLITE_PATH=/app/data/llm-shadow-proxy.sqlite
PRIMARY_URL=https://primary.example.com/v1/mock
CANDIDATE_URL=https://candidate.example.com/v1/mock
APP_HTTP_CLIENT_CONNECT_TIMEOUT_MS=1000
APP_HTTP_CLIENT_READ_TIMEOUT_MS=2000
SHADOW_QUEUE_BACKEND=redis
SHADOW_QUEUE_STREAM=llm-shadow:shadow-jobs
SHADOW_QUEUE_GROUP=llm-shadow-proxy
SHADOW_QUEUE_DLQ=llm-shadow:shadow-jobs:dead-letter
SHADOW_QUEUE_RETRY_ZSET=llm-shadow:shadow-jobs:retry
```

If `PRIMARY_URL` or `CANDIDATE_URL` are blank, the app uses the internal mock endpoints.

## DigitalOcean Deployment

Use DigitalOcean App Platform for the Spring Boot app and DigitalOcean Managed Caching for Valkey as the Redis-compatible queue service.

Recommended App Platform settings:

- Type: Web Service
- Build method: Dockerfile
- HTTP port: `8080`
- Health check path: `/actuator/health`
- Runtime environment variables:
  - `API_KEY` (required for auth; keep your existing value — nothing breaks)
  - `JWT_SECRET` (optional; enables JWT Bearer auth — use at least 32 characters)
  - `JWT_EXPIRATION_SECONDS` (optional; default `3600`)
  - `JWT_ISSUER` (optional; default `llm-shadow-proxy`)
  - `REDIS_URL`
  - `SQLITE_PATH`
  - optional `PRIMARY_URL`
  - optional `CANDIDATE_URL`

### DigitalOcean JWT rollout (backward compatible)

Your deployed app at `https://walrus-app-vu7mk.ondigitalocean.app` already uses `API_KEY`. To add JWT **without breaking existing clients**:

1. In App Platform → **Settings** → **Environment Variables**, add:
   - `JWT_SECRET` = a new long random string (32+ characters, encrypted)
   - Keep your existing `API_KEY` unchanged
2. Redeploy the app (push this JWT code change first, then redeploy).
3. Existing curl/Swagger clients using `X-API-Key` continue to work.
4. New JWT flow:
   ```bash
   curl -s -X POST https://walrus-app-vu7mk.ondigitalocean.app/auth/token \
     -H 'X-API-Key: YOUR_EXISTING_API_KEY'

   curl -s https://walrus-app-vu7mk.ondigitalocean.app/metrics \
     -H 'Authorization: Bearer <accessToken from above>'
   ```

Queue integration steps:

1. Create a Managed Caching for Valkey cluster in DigitalOcean.
2. Copy the connection string from the database connection details. Use the TLS connection string when available, usually shaped like `rediss://default:password@host:port`.
3. In App Platform, add `REDIS_URL` as an encrypted runtime environment variable with that connection string.
4. Deploy the app. On startup, `RedisShadowJobQueue` creates the stream consumer group if it does not exist.
5. Confirm queue activity in logs:

```text
event=shadow_job_queued requestId=...
event=llm_shadow_mismatch requestId=...
event=shadow_dead_lettered requestId=...
```

For App Platform database binding, DigitalOcean also supports bindable variables for managed databases. If the Valkey component is named `queue`, set `REDIS_URL` from the connection string bindable variable if one is available, or compose it from the host, port, username, and password values.

SQLite note: App Platform containers are replaceable. If you need mismatch records to survive redeploys, attach persistent storage and set `SQLITE_PATH` to the mounted path. If this is only a demo, runtime logs plus ephemeral SQLite are acceptable.

## CI

GitHub Actions is configured in `.github/workflows/ci.yml` and runs:

```bash
mvn -B test
```

## Remaining Production Gaps

- Redis Streams is lightweight and good for this assignment, but a production system may want managed Kafka, SQS, or a dedicated job queue depending on ordering, replay, and throughput needs.
- The retry zset and dead-letter stream are intentionally simple.
- Resilience4j circuit breaker state is still in-memory per app instance. For multiple production instances, use gateway/service-mesh circuit breaking or an explicitly shared breaker state.
- SQLite is fine for demo mismatch storage, but multi-instance production deployments should use a shared database.
- API key auth remains supported for simple service-to-service access. JWT adds expiring Bearer tokens without removing API key support.
