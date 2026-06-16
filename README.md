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
  Worker --> Executor["ThreadPoolTaskExecutor"]
  Executor --> Candidate["Candidate LLM"]
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
POST /mock/primary
POST /mock/candidate
GET  /actuator/health
GET  /v3/api-docs
GET  /swagger-ui/index.html
```

## Swagger

```text
http://localhost:8080/swagger-ui/index.html
```

If `API_KEY` is configured, click **Authorize** and enter the key. Swagger sends it as `X-API-Key`.

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

## Retry And Circuit Breaker

Candidate failures do not affect the Primary response. Failed shadow jobs retry through Redis:

```properties
shadow.retry.max-attempts=3
shadow.retry.backoff-ms=1000
```

The lightweight circuit breaker opens after repeated Candidate failures:

```properties
shadow.circuit-breaker.enabled=true
shadow.circuit-breaker.failure-threshold=3
shadow.circuit-breaker.open-duration-ms=10000
```

When the circuit is open, the job is moved to the Redis retry zset until the open period expires. After max attempts, the job is written to the Redis dead-letter stream.

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
REDIS_URL=redis://localhost:6379
SQLITE_PATH=/app/data/llm-shadow-proxy.sqlite
PRIMARY_URL=https://primary.example.com/v1/mock
CANDIDATE_URL=https://candidate.example.com/v1/mock
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
  - `API_KEY`
  - `REDIS_URL`
  - `SQLITE_PATH`
  - optional `PRIMARY_URL`
  - optional `CANDIDATE_URL`

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
- The circuit breaker is in-memory per app instance.
- SQLite is fine for demo mismatch storage, but multi-instance production deployments should use a shared database.
- API key auth is simple. Production systems usually need identity, rotation, audit, and secret management.
