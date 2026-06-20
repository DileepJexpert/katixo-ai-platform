# EXTRACTION-PLAN.md — shared `katixo-ai-platform`

**Status: APPLIED in all three repos.** Both apps depend on `katixo-ai-commons:0.1.0` and every GPU
call (docai's Ollama; Studio's ComfyUI, rembg, esrgan, whisper, copilot Ollama) is wrapped in
`GpuResourceGuard.runExclusively`. Branch `claude/charming-faraday-hp9nw1` in each repo. Test bars
green: docai 36/36 (offline + privacy intact), Studio 2/2, commons 8/10 (+2 Docker-skipped). Design
decisions taken: thin `SidecarClient` base (no polling template); job DTOs as shared vocabulary only;
dedicated `katixo_gpu` lock-authority DB; TTS (CPU) and PaddleOCR (light/CPU) left unguarded.

This is the Phase-0 discovery + proposal. It says exactly what moves into the shared
library, what stays app-specific, the interface signatures, and how both apps route GPU
work through one guard. Read the **Open decisions** section at the end first if you only
have a minute — those are the calls I want you to make before I execute.

---

## 1. What I read

| Area | image-generator (`com.katixo.studio`, mature) | katixo-docai (`com.katixo.ai`, fresh) |
|------|-----------------------------------------------|----------------------------------------|
| HTTP stack | raw JDK `java.net.http.HttpClient` per client | Spring `RestClient` (2 beans in `HttpClientConfig`) |
| Sidecar clients | `ComfyUiClient` (submit/poll/fetch + WS progress), `RembgClient`, `EsrganClient`, `WhisperClient`, `TtsClient`, copilot `OllamaClient` | `OllamaLlmClient` (`LlmClient`), `PaddleOcrClient` (`OcrClient`) |
| Call shape | `connectTimeout(10s)`, per-request `timeout(5m)`, `statusCode()/100 != 2` → `IOException`; **no retry, no idempotency, no shared base** | `.retrieve().body()`, `RestClientException` → `UpstreamUnavailableException`; **no retry, no idempotency, no shared base** |
| Async model | Redis queue + JPA `Job` entity + single `JobWorker` (concurrency=1) + WS progress | **Synchronous** `ExtractionPipeline`, no jobs |
| Config prefix | `katixo.*` (`KatixoProperties` record) | `katixo.ai.*` (`AiProperties`) |
| Persistence | Postgres `katixo` + **Flyway** (`V1__initial_schema.sql`), `ddl-auto: validate` | Postgres `katixo_ai`, **no Flyway**, `ddl-auto: update` |
| Tests | 1 test (`AgentServiceTest`) — pure Mockito, **no Spring context, no DB** | 9 tests incl. `OfflineEndToEndTest` + `PrivacyGuardTest` — **full context on H2 in-memory, must stay offline (no Docker/network)** |

### The two facts that drive the whole design

1. **The GPU collision is between the two apps, never inside one.** image-generator is
   already single-threaded at the GPU (one `JobWorker`, concurrency=1). docai is
   synchronous. Each app serializes itself. What nothing prevents today is
   *image-gen rendering while docai extracts* — both hit the one 4060 and OOM.

2. **Postgres advisory locks are scoped per-database, not per-server.** The lock tag is
   `{database OID, key}`. The two apps currently use **different databases**
   (`katixo` vs `katixo_ai`), so the *same* lock key in each would be two *different*
   locks and would **not** serialize anything. This is the single easiest way to ship a
   guard that looks correct and silently does nothing. The guard therefore must talk to a
   **shared lock authority** — one agreed database both apps connect to *for locking* —
   not each app's own business DB. (See §4.)

---

## 2. Design principle: share the abstraction, not the implementation

ComfyUI and Ollama are different engines; rembg/esrgan/whisper are one-shot POSTs while
ComfyUI is submit→poll→fetch; docai uses RestClient while image-gen uses JDK HttpClient.
The genuinely common surface is **cross-cutting plumbing**, not engine behavior:

- a uniform way to call a localhost sidecar (base-URL handling, timeout, retry/backoff,
  idempotency key, typed failure, a `health()` contract);
- one **GPU mutual-exclusion guard** (the real prize);
- shared config-prefix conventions and a tiny logging helper;
- a small, optional shared **vocabulary** of job DTOs.

Everything engine-specific (the ComfyUI graph templating + poll loop, the Ollama JSON-mode
body, the OCR multipart, docai's validation/privacy, image-gen's Redis/JPA job machinery)
**stays in its app.** If a future reviewer sees ComfyUI or Ollama *request-building* or any
business logic land in commons, that's the over-extraction smell — it should not happen.

---

## 3. `katixo-ai-commons` — exact contents (thin)

Maven: parent `katixo-ai-platform` (pom packaging, aggregator) → module `katixo-ai-commons`
(`com.katixo.ai:katixo-ai-commons:0.1.0`, JAR). Java base package **`com.katixo.ai.commons`**
(NOT bare `com.katixo.ai` — that's docai's root; avoid split packages).

Compile deps: **`slf4j-api` only.** No Spring, no JPA, no Jackson, no Redis. Pure JDK +
slf4j so it stays a thin library and is unit-testable without a container.
Test deps (do not leak to consumers): JUnit 5, AssertJ, Testcontainers-postgresql + the
postgres JDBC driver.

```
katixo-ai-platform/
├── pom.xml                      # aggregator (packaging=pom)
├── EXTRACTION-PLAN.md           # this file
├── MIGRATION.md                 # Phase-1 deliverable (how apps consume + version bump)
└── katixo-ai-commons/
    ├── pom.xml
    └── src/
        ├── main/java/com/katixo/ai/commons/
        │   ├── gpu/             # GpuResourceGuard + impls + config + exceptions   ← the point
        │   ├── sidecar/         # SidecarClient base + config + typed errors
        │   ├── dto/             # JobStatus, GpuJobRequest, GpuJobResult (shared vocabulary)
        │   └── observability/   # GpuActivityLog (tiny structured-logging helper)
        └── test/java/com/katixo/ai/commons/gpu/
            ├── InProcessGpuGuardTest.java          # pure threads, always runs
            └── PostgresAdvisoryGpuGuardTest.java   # Testcontainers, Docker-gated
```

### 3a. `gpu/` — the centerpiece (Phase 2)

```java
package com.katixo.ai.commons.gpu;

public interface GpuResourceGuard {
    /** Acquire the single-GPU lock, run task, ALWAYS release. */
    <T> T runExclusively(String jobLabel, GpuTask<T> task) throws Exception;
    default void runExclusively(String jobLabel, GpuRunnable task) throws Exception { ... }
    GpuGuardStatus status();                 // best-effort: {held, currentLabel}

    @FunctionalInterface interface GpuTask<T> { T call() throws Exception; }
    @FunctionalInterface interface GpuRunnable { void run() throws Exception; }
}

public record GpuGuardConfig(
        long lockKey,            // default KATIXO_GPU_LOCK_KEY = 0x4B_41_49_47_50_55L ("KAIGPU")
        Duration acquireTimeout, // default 60s  — fail fast past this
        Duration pollInterval,   // default 250ms — pg_try_advisory_lock poll cadence
        Duration maxHold,        // default 15m  — watchdog releases a stuck holder
        Mode mode) {            // POSTGRES | IN_PROCESS
    enum Mode { POSTGRES, IN_PROCESS }
}

// Thrown when the lock can't be acquired within acquireTimeout — caller decides to queue/retry.
public class GpuBusyException extends RuntimeException { String jobLabel; Duration waited; }
// Thrown when the lock infrastructure itself fails (SQL error, no connection).
public class GpuGuardException extends RuntimeException { }
```

**`PostgresAdvisoryGpuGuard implements GpuResourceGuard`** — ctor
`(javax.sql.DataSource lockAuthority, GpuGuardConfig cfg)`. Algorithm:

```
Connection c = lockAuthority.getConnection(); c.setAutoCommit(true);   // dedicated, NON-transactional
boolean acquired = false;
try {
    long deadline = nanoTime() + acquireTimeout;
    while (nanoTime() < deadline) {
        if (selectBool(c, "SELECT pg_try_advisory_lock(?)", lockKey)) { acquired = true; break; }
        sleep(pollInterval);
    }
    if (!acquired) throw new GpuBusyException(jobLabel, acquireTimeout);
    Watchdog w = watchdog.arm(maxHold, () -> closeQuietly(c));  // closing the session releases its locks
    try { return task.call(); } finally { w.disarm(); }
} finally {
    if (acquired) selectBool(c, "SELECT pg_advisory_unlock(?)", lockKey);
    closeQuietly(c);                                            // backstop: session end frees the lock
}
```

- **Session-level** `pg_try_advisory_lock` (not `pg_advisory_xact_lock`) so we do NOT hold an
  open DB transaction for a 10-minute ComfyUI render — we hold a connection + a session lock,
  no idle-in-transaction. `try_` + poll loop gives a real acquire-timeout and *never blocks
  forever* (the prompt's requirement). Lock + unlock happen on the **same** pinned connection.
- **Max-hold watchdog**: if a GPU call wedges, the watchdog closes the pinned connection;
  Postgres frees session advisory locks on session end, so the lock can't be stuck forever.

**`InProcessGpuGuard implements GpuResourceGuard`** — ctor `(GpuGuardConfig cfg)`. Uses a
process-wide `Semaphore(1, true)` keyed by `lockKey` (static map, so multiple instances in one
JVM share it). `tryAcquire(acquireTimeout)` → `GpuBusyException` on timeout; release in finally.
**This serializes only within one JVM.** Its jobs: (a) keep docai's H2 offline tests green
(H2 has no `pg_advisory_lock`), and (b) prove the guard *contract* in a fast, Docker-free unit
test. It is **never** the cross-app mechanism — production is always `POSTGRES`.

### 3b. `sidecar/` — thin, HTTP-stack-agnostic base

The base owns plumbing only; the subclass performs the actual exchange with *its own* HTTP
client (RestClient in docai, HttpClient in image-gen). It does **not** import either.

```java
package com.katixo.ai.commons.sidecar;

public abstract class SidecarClient {
    protected final String baseUrl;          // trailing slash trimmed
    protected final SidecarConfig config;    // name, timeouts, maxRetries, backoffBase

    protected String resolve(String path);
    protected String newIdempotencyKey();    // UUID; subclass attaches as header / ComfyUI client_id
    protected <T> T withRetry(String op, SidecarCall<T> call); // backoff; exhaustion → SidecarUnavailableException
    public abstract SidecarHealth health();  // subclass pings its own health path

    @FunctionalInterface protected interface SidecarCall<T> { T run() throws Exception; }
}

public record SidecarConfig(String name, Duration connectTimeout, Duration readTimeout,
                            int maxRetries, Duration backoffBase) { /* sensible defaults */ }
public record SidecarHealth(boolean reachable, String name, String detail) { }
public class SidecarUnavailableException extends RuntimeException { String service; }
```

Refactor targets (extend the base, keep their bodies):
- docai `OllamaLlmClient` and `PaddleOcrClient` extend `SidecarClient`; keep RestClient calls,
  keep JSON-mode/multipart bodies. `health()` wraps the existing `/api/ps` and `/health` pings.
- **docai's `UpstreamUnavailableException` will `extends SidecarUnavailableException`** so its
  existing `@ExceptionHandler(UpstreamUnavailableException.class)` (→ HTTP 503) keeps working
  unchanged. Zero web-layer churn.
- image-gen `ComfyUiClient`/`RembgClient`/`EsrganClient`/`WhisperClient`/`OllamaClient` extend
  the base for retry/timeout/idempotency/health; their request-building stays put.

**Deliberately NOT extracted now:** a generic `submit/poll/fetch` polling template. Only
ComfyUI has that shape; generalizing it for a single polling sidecar is speculative. ComfyUI
keeps its bespoke poll loop and extends the *thin* base for the cross-cutting bits. (If a
second polling sidecar ever appears, promote a `PollingSidecarClient` then — noted in MIGRATION.md.)

### 3c. `dto/` — shared vocabulary (optional adoption)

```java
public enum JobStatus { QUEUED, RUNNING, DONE, FAILED }
public record GpuJobRequest(String type, String label, Map<String,Object> params, String idempotencyKey) {}
public record GpuJobResult(JobStatus status, String resultRef, String error, long latencyMs) {}
```

Honest note: image-gen already has a JPA `Job` entity + its own `JobStatus`/`JobType` enums
wired to Redis/Postgres/WebSocket — that is **infrastructure and stays put**. docai is
synchronous and has no jobs. So these DTOs are the **canonical vocabulary** for the future
broker service and any new shared flow; I am **not** ripping out image-gen's entity or forcing
docai to adopt jobs. (See Open decision #3 — this is the weakest "shared" item and I want a
light touch.)

### 3d. `observability/`

`GpuActivityLog` — one tiny helper to log `acquire/hold/release` with `jobLabel`, wait-ms and
hold-ms at a consistent format, so the two apps' GPU contention is greppable in one place.

---

## 4. The shared lock authority (the non-obvious bit)

Because advisory locks are per-database, the guard gets its **own** `DataSource` pointing at a
database both apps share *for locking only*:

- Recommended: a dedicated, near-empty DB **`katixo_gpu`** on the same local Postgres. Both apps
  set identical `katixo.ai.gpu.lock-datasource.*`. No tables needed — `pg_advisory_lock` works on
  a bare connection, so **no Flyway/DDL** is added to either app (keeps docai's no-Flyway setup
  untouched).
- Alternative: point both lock-DataSources at one existing DB (e.g. `katixo`). Works, but couples
  docai to image-gen's DB. I recommend the dedicated DB.

New shared config block (added to *both* apps, additive):

```yaml
katixo:
  ai:
    gpu:
      lock-mode: postgres            # postgres | in-process   (tests override to in-process)
      acquire-timeout: 60s
      max-hold: 15m
      lock-datasource:
        url: jdbc:postgresql://localhost:5432/katixo_gpu   # IDENTICAL in both apps
        username: katixo
        password: katixo
```

Each app wires one `GpuResourceGuard` bean in a ~15-line `@Configuration` (commons stays
Spring-free): build the lock `DataSource`, pick impl from `lock-mode`. Tests set
`lock-mode: in-process` → no DataSource needed, no Docker.

---

## 5. Where every GPU call gets wrapped

**Decision: wrap at the GPU-call boundary inside each sidecar client, not per-job.** Reason:
`LeadScrapeHandler` interleaves a long *non-GPU* web scrape with intermittent *GPU* Ollama
calls — a per-job `usesGpu` flag would either hold the GPU during scraping or skip the LLM
calls. Wrapping the actual sidecar call is precise, DRY, and exhaustive, and it means
**image-gen's `AgentServiceTest` needs no change** (it mocks `OllamaClient`).

| App | Client / method | GPU? | Action |
|-----|-----------------|------|--------|
| image-gen | `ComfyUiClient.generateImage` / `generateVideo` | ✅ | wrap in `runExclusively` |
| image-gen | `RembgClient.removeBackground` | ✅ | wrap |
| image-gen | `EsrganClient.upscale` | ✅ | wrap |
| image-gen | `WhisperClient.transcribe` | ✅ | wrap |
| image-gen | `OllamaClient.chat` / `chatStream` / `chatWithTools` | ✅ | wrap (covers Copilot + Agent + lead outreach) |
| image-gen | `OllamaClient.listModels` (`/api/tags`) | ❌ metadata | leave |
| image-gen | `TtsClient.synthesize` (Piper) | ⚠️ CPU | **leave** (flag — see decision #4) |
| image-gen | `LeadScraper` (jsoup) | ❌ | leave |
| docai | `OllamaLlmClient.generate` | ✅ | wrap (initial + repair calls each acquire/release) |
| docai | `PaddleOcrClient.ocr` | ⚠️ light/often-CPU | **leave** (flag — decision #4) |

Constraint documented in code + MIGRATION.md: **never nest `runExclusively`** (the agent loop
and lead handler call sequentially, so we're safe today; nesting on distinct connections would
deadlock the PG lock).

---

## 6. Testing strategy (acceptance: "two concurrent GPU requests are serialized")

- `InProcessGpuGuardTest` — two threads call `runExclusively` with an overlap detector; assert
  zero overlap; assert a third with a short `acquireTimeout` throws `GpuBusyException`. Pure
  threads, **always runs**, no Docker.
- `PostgresAdvisoryGpuGuardTest` — Testcontainers Postgres; **two separate DataSources** (=two
  app processes) on the **same DB**; thread A holds, thread B must wait; assert no overlap and a
  short-timeout caller fails fast. Proves the *real* cross-process serialization. Guarded by
  `assumeTrue(DockerClientFactory.instance().isDockerAvailable())` so a Docker-less `mvn install`
  still passes on the in-process test.
- Regression bars (unchanged, must stay green): docai `OfflineEndToEndTest` + `PrivacyGuardTest`
  on H2 with `lock-mode: in-process`; image-gen `AgentServiceTest`.

---

## 7. Per-app refactor (Phase 3) and why nothing regresses

**katixo-docai**
1. Add `com.katixo.ai:katixo-ai-commons:0.1.0`.
2. `OllamaLlmClient`, `PaddleOcrClient` extend `SidecarClient`; `UpstreamUnavailableException
   extends SidecarUnavailableException`. Behavior identical.
3. Inject `GpuResourceGuard` into `OllamaLlmClient`; wrap the `generate` body. The guard does a
   **DB lock only — no network**, so `PrivacyGuard`/loopback enforcement is untouched.
4. `application-test.yml`: `katixo.ai.gpu.lock-mode: in-process`. Offline tests never touch
   `pg_advisory_lock` → stay green, still no Docker/network.

**image-generator**
1. Add the dep.
2. The five GPU clients extend `SidecarClient`; inject `GpuResourceGuard`; wrap the GPU calls
   per §5. `JobWorker`, handlers, Redis, JPA untouched.
3. `AgentServiceTest` is pure Mockito → unaffected (we wrap inside `OllamaClient`, which it mocks).

**Consumption for now:** `mvn install` commons to local `~/.m2`; both apps depend on it.
GitHub Packages can come later (noted in MIGRATION.md).

---

## 8. Acceptance-criteria map

| Criterion | Covered by |
|-----------|-----------|
| EXTRACTION-PLAN.md reviewed before refactor | this file → **stop here** |
| commons builds + `mvn install`s | §3 module, slf4j-only deps |
| Guard test proves serialization | §6 (in-process always; Testcontainers for real cross-process) |
| Both apps build green, suites pass incl. docai offline/privacy | §7 (`in-process` in tests; no DDL; privacy untouched) |
| Every GPU call routed through the guard | §5 table |
| MIGRATION.md (consume + version bump) | Phase-1 deliverable |
| New branch per repo, nothing to `main` | see Open decision #1 |

---

## 9. Explicitly OUT of scope (will NOT build)

- No standalone broker/scheduler service (deferred; the `GpuResourceGuard` is the seam it'll be
  promoted from). No queue/priorities/multi-GPU fan-out.
- No Flutter / business-logic / accounting changes beyond routing GPU calls.
- No generic polling template, no engine request-building in commons, no forced DTO migration.
- No merge to `main` in any repo.

---

## 10. Open decisions — please confirm before I execute

1. **Branch name.** Your prompt says `feat/shared-ai-platform`; my session config pins all three
   repos to `claude/charming-faraday-hp9nw1` and forbids pushing elsewhere without explicit
   permission. Both are "not main, separate branch for review." I need you to pick.
2. **`SidecarClient` depth.** Recommend the **thin** base (retry/timeout/idempotency/health/typed
   errors) and leave ComfyUI's submit/poll/fetch in-app. Alternative: also extract a generic
   polling template now (more "shared", but speculative for one polling sidecar).
3. **Job DTOs.** Recommend ship them as shared **vocabulary** only — do **not** migrate image-gen's
   JPA `Job`/enums or add jobs to docai. Confirm you don't want a deeper DTO unification.
4. **Guard scope at the edges.** Recommend leaving **Piper TTS** (CPU) and **PaddleOCR** (light,
   often CPU) unguarded to avoid needless serialization. Say the word and I'll guard them too.
5. **Lock authority DB.** Recommend a dedicated shared **`katixo_gpu`** DB (both apps point their
   lock-DataSource there). Alternative: reuse `katixo`. This is the thing that *silently* breaks
   serialization if the two apps don't share it.
