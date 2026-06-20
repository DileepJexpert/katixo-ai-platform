# MIGRATION.md — consuming `katixo-ai-commons`

How `katixo-docai` and `image-generator` depend on the shared library, how the single-GPU guard
is wired, and how to bump the library version. Pairs with `EXTRACTION-PLAN.md` (the design).

> **Current state:** both apps are wired. `katixo-docai` and `image-generator` depend on
> `katixo-ai-commons:0.1.0` and route every GPU call through `GpuResourceGuard`. See the per-repo
> commit on `claude/charming-faraday-hp9nw1`.

---

## 1. What the library gives you

`com.katixo.ai:katixo-ai-commons:0.1.0` (a thin JAR, slf4j-only at runtime):

| Package | What |
|---------|------|
| `com.katixo.ai.commons.gpu` | `GpuResourceGuard` + `PostgresAdvisoryGpuGuard` (prod) + `InProcessGpuGuard` (tests), `GpuGuardConfig`, `GpuBusyException`, `GpuGuardException` |
| `com.katixo.ai.commons.sidecar` | `SidecarClient` base (base-URL, idempotency key, retry/backoff, `probe()`), `SidecarConfig`, `SidecarHealth`, `SidecarUnavailableException` |
| `com.katixo.ai.commons.dto` | `JobStatus`, `GpuJobRequest`, `GpuJobResult` (shared vocabulary) |
| `com.katixo.ai.commons.observability` | `GpuActivityLog` |

It contains no Spring, no business logic, no engine-specific request building.

---

## 2. Build & publish the library (local `~/.m2` for now)

```bash
cd katixo-ai-platform
mvn -q install        # builds + tests + installs com.katixo.ai:katixo-ai-commons:0.1.0 to ~/.m2
```

The real-Postgres advisory-lock test (`PostgresAdvisoryGpuGuardTest`) self-skips when Docker is
unavailable; `InProcessGpuGuardTest` always runs and proves the guard contract. With Docker present
you get the full cross-process proof.

Both apps consume it as an ordinary Maven dependency (already added to their `pom.xml`):

```xml
<dependency>
    <groupId>com.katixo.ai</groupId>
    <artifactId>katixo-ai-commons</artifactId>
    <version>0.1.0</version>
</dependency>
```

> GitHub Packages can host this later; for the single-box setup, `mvn install` to the local
> repository is enough. To switch, add the `<distributionManagement>` + a `<repository>` entry in
> each consumer and `mvn deploy` instead of `install`.

---

## 3. The single most important step: the shared lock authority

The guard serializes the two apps with a **PostgreSQL advisory lock**. Advisory locks are scoped to
a **database** (the lock tag includes the database OID), so:

> **Both apps must point their GPU lock-DataSource at the SAME database**, or the lock serializes
> nothing — even on the same Postgres server. The two apps' business databases differ
> (`katixo_ai` vs `katixo`), so they must NOT use those for locking.

**One-time setup on the box:**

```sql
CREATE DATABASE katixo_gpu;           -- a dedicated, near-empty lock-authority DB (no tables needed)
```

Both apps default to it:

```yaml
katixo:
  ai:
    gpu:
      lock-mode: postgres
      lock-datasource:
        url: jdbc:postgresql://localhost:5432/katixo_gpu   # IDENTICAL in both apps
        username: katixo
        password: katixo
```

- `lock-key` defaults to the shared `GpuGuardConfig.DEFAULT_LOCK_KEY` in both apps — keep it identical
  if you ever override it.
- image-generator exposes env overrides: `GPU_LOCK_MODE`, `GPU_LOCK_DB_URL`, `GPU_LOCK_DB_USER`,
  `GPU_LOCK_DB_PASSWORD`.
- No tables/DDL are created in `katixo_gpu`; the advisory lock needs only a connection. (This is why
  it didn't touch docai's Flyway-less schema or image-gen's Flyway migrations.)

**Tests** set `lock-mode: in-process` (docai's `application-test.yml`; image-gen via
`GPU_LOCK_MODE=in-process` if it ever adds a context test) so no Postgres/Docker is needed and
docai's offline/H2 suite never calls `pg_advisory_lock`.

---

## 4. Where GPU calls are guarded

Wrapped at the GPU-call boundary inside each sidecar client (so the mixed lead-scrape job, which
interleaves web scraping with LLM calls, is handled correctly):

| App | Guarded (`runExclusively`) | Not guarded |
|-----|----------------------------|-------------|
| image-generator | `ComfyUiClient` image + video, `RembgClient`, `EsrganClient`, `WhisperClient`, `OllamaClient` chat/chatStream/chatWithTools | `OllamaClient.listModels` (metadata), `TtsClient` (Piper, CPU), lead scraping (jsoup) |
| katixo-docai | `OllamaLlmClient.generate` (text + repair) | `PaddleOcrClient.ocr` (light/CPU) |

`GpuBusyException` (acquire-timeout) maps to HTTP 503 in both apps' exception handlers; in
image-gen's job path it marks the job failed with a "GPU busy" message (resubmit to retry).

---

## 5. Verifying serialization end-to-end

- **Unit (always):** `mvn -q test` in `katixo-ai-platform` → `InProcessGpuGuardTest` proves two
  concurrent GPU jobs never overlap and a held GPU fails the next caller fast.
- **Real Postgres (Docker):** run the same module with Docker available → `PostgresAdvisoryGpuGuardTest`
  proves two independent DataSources (= two apps) are serialized by the advisory lock.
- **Manual, both apps live:** start both apps against the same `katixo_gpu`, fire an image render and a
  docai extraction at once, and watch the `katixo.ai.gpu.activity` / guard debug logs — one waits for
  the other. Point one app at a *different* lock DB and you'll see them overlap: that's the
  misconfiguration this guard is designed to make visible.

---

## 6. Bumping the library version

1. Edit the version in `katixo-ai-platform/pom.xml` and `katixo-ai-commons/pom.xml` (keep them in
   step; the module inherits the parent version), e.g. `0.1.0` → `0.2.0`.
2. `cd katixo-ai-platform && mvn -q install`.
3. Bump the `<version>` in each consumer's `pom.xml` (`katixo-docai`, `image-generator`).
4. Rebuild both apps (`mvn -q test`) to confirm green against the new JAR.

Keep changes backward-compatible within a minor line; the guard's lock key and mode are the only
cross-app contract that must stay aligned across both consumers at runtime.
