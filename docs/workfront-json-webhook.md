# Workfront Fusion — JSON conversion & signed webhook delivery

## Overview

The [Workfront CSV export](./workfront-csv-export.md) writes one CSV per configured content tree
into `/content/dam/mysite/workfront-dashboard/`. Workfront Fusion needs that data to build
dashboards/reports, but sending everything at request time risks timeouts. So the data is
**pre-produced as JSON and pushed to a Fusion webhook** in two independent, resilient pipelines:

1. **Convert** — each `*.csv` in a configurable input folder is converted into its own JSON dataset
   in an output folder. Each file is converted **independently**; one failure never affects the others.
2. **Send** — each JSON dataset is POSTed to a single Fusion **webhook** as a **separate request**,
   signed with **HMAC-SHA256** over the request body so Fusion can verify the request is genuine.

Conversion and sending run on **independent schedules** (convert nightly, send on weekends by default).

```
CSV folder (/content/dam/mysite/workfront-dashboard)
      │  WorkfrontJsonConverterScheduler (cron, default nightly)
      ▼
WorkfrontJsonConverterService  ── per CSV (isolated, retries) ──▶ json/<name>.json
      │
      ▼
json folder (/content/dam/mysite/workfront-dashboard/json)
      │  WorkfrontWebhookScheduler (cron, default weekend)
      ▼
WorkfrontWebhookService ── per JSON (isolated, retries) ──▶ POST <webhookUrl>
                                                            X-Workfront-Signature: sha256=<hex>
                                                            X-Workfront-Dataset: <name>
```

---

## Components

| Class | Role |
|---|---|
| `core/.../util/SimpleCsvParser` | RFC 4180 CSV parser (quoted fields, doubled quotes, embedded commas/newlines, CRLF/LF/CR). Inverse of the generator's escaping. |
| `core/.../util/HmacUtil` | `sha256Hex(secret, body)` — HMAC-SHA256 → lowercase hex. |
| `core/.../services/WorkfrontJsonConverterService(+Impl)` | Reads a CSV asset, builds a JSON dataset, writes `<name>.json` to the output folder. |
| `core/.../schedulers/WorkfrontJsonConverterScheduler` | Lists `*.csv` in the input folder; converts each with per-file isolation + in-run retries. |
| `core/.../services/WorkfrontWebhookService(+Impl)` | Signs a JSON dataset body with HMAC-SHA256 and POSTs it to the webhook via `HttpURLConnection`. |
| `core/.../schedulers/WorkfrontWebhookScheduler` | Lists `*.json` in the output folder; sends each with per-file isolation + in-run retries. |

Both schedulers use the existing `workfront-csv-write` subservice (service user
`workfront-csv-service`); its ACL already grants read on `/content` and read/write on the dashboard
DAM folder (which covers the `json` subfolder). No new service user or repoinit is required.

---

## Dataset JSON shape

One file per CSV, e.g. `…/workfront-dashboard/json/us-en.json`:

```json
{
  "dataset": "us-en",
  "generatedAt": "2026-09-08T02:00:00.123Z",
  "recordCount": 980,
  "records": [
    {
      "hash": "0b1c…e9",
      "title": "Homepage",
      "path": "/content/mysite/us/en",
      "brand": "mysite",
      "lastModified": "2026-07-30T10:15:00.000+02:00",
      "modifiedBy": "jdoe",
      "published": true,
      "nextReviewDate": "2026-12-31",
      "daysForNextReview": 114,
      "franchise": "retail",
      "pageOwners": "borrow",
      "template": "/conf/mysite/settings/wcm/templates/page-content"
    }
  ]
}
```

- `published` is a real boolean; `daysForNextReview` is a real integer (`null` when the CSV cell is blank/non-numeric).
- `franchise` / `pageOwners` are the stored **keys** (as in the CSV), not the display labels.
- Rows without a `Path` are treated as malformed and skipped (logged as `WARN`); the rest of the file still converts.

---

## Webhook signature (how Fusion verifies)

Each POST carries:

```
Content-Type: application/json; charset=utf-8
X-Workfront-Signature: sha256=<hex>
X-Workfront-Dataset: <dataset name>
<raw json body>
```

The signature is `HMAC-SHA256(webhookSecret, rawBody)` as lowercase hex. On the Fusion side,
recompute the HMAC over the **exact raw body received** with the same shared secret and compare it
(constant-time) to the value after the `sha256=` prefix. Reject if they differ.

The header names are configurable (`signatureHeader`, `datasetHeader`).

---

## Configuration (OSGi)

Service configs live in `ui.config/.../osgiconfig/config/`; the two **scheduler** configs live in
`config.author/` so they run on **author only** (where the CSV/JSON live) — avoiding double sends
from publish.

### `com.mysite.core.services.impl.WorkfrontJsonConverterServiceImpl`
| Property | Default | Description |
|---|---|---|
| `inputFolder` | `/content/dam/mysite/workfront-dashboard` | DAM folder scanned for source CSVs |
| `outputFolder` | `/content/dam/mysite/workfront-dashboard/json` | DAM folder JSON datasets are written into |

### `com.mysite.core.services.impl.WorkfrontWebhookServiceImpl`
| Property | Default | Description |
|---|---|---|
| `webhookUrl` | *(empty)* | Fusion webhook URL each dataset is POSTed to |
| `webhookSecret` | *(empty)* | Shared secret for the HMAC signature — **set per environment; never commit** |
| `signatureHeader` | `X-Workfront-Signature` | Header carrying `sha256=<hex>` |
| `datasetHeader` | `X-Workfront-Dataset` | Header carrying the dataset name |
| `connectTimeoutMs` | `10000` | HTTP connect timeout |
| `readTimeoutMs` | `30000` | HTTP read timeout |

> **Security:** `webhookSecret` (and often `webhookUrl`) must not be committed. The repo ships them
> empty; set real values per environment via the OSGi web console (Configuration →
> *Workfront Webhook Service*) or a non-committed run-mode config. **The service refuses to send when
> the URL or secret is empty**, so an unsigned request is never made.

### `com.mysite.core.schedulers.WorkfrontJsonConverterScheduler` (config.author)
| Property | Default | Description |
|---|---|---|
| `scheduler.expression` | `0 0 2 * * ?` | Cron — conversion run (nightly) |
| `scheduler.concurrent` | `false` | Allow concurrent runs |
| `maxAttempts` | `3` | Attempts per file before giving up for this run |
| `retryBackoffSeconds` | `5` | Pause between retry attempts for the same file |
| `pauseBetweenFilesSeconds` | `5` | Cool-down after each file |

### `com.mysite.core.schedulers.WorkfrontWebhookScheduler` (config.author)
| Property | Default | Description |
|---|---|---|
| `scheduler.expression` | `0 0 3 ? * SAT` | Cron — send run (weekend) |
| `scheduler.concurrent` | `false` | Allow concurrent runs |
| `maxAttempts` | `3` | Attempts per dataset before giving up for this run |
| `retryBackoffSeconds` | `5` | Pause between retry attempts for the same dataset |
| `pauseBetweenFilesSeconds` | `5` | Cool-down after each dataset |

---

## Resilience & logging

- **Isolation:** every file is converted/sent inside its own try/catch — a failure never stops the batch.
- **In-run retries:** each file is retried up to `maxAttempts` with a `retryBackoffSeconds` pause;
  anything still failing is retried on the **next scheduled run**.
- **Never send unsigned:** missing URL/secret is an `ERROR` and the send is skipped.
- **Logging levels** (`com.mysite.core.schedulers` / `com.mysite.core.services`):
  - `INFO` — activate config summary; run start/finish; per-file success (dataset, record count / HTTP status);
    the **full webhook request** (URL, headers, signature, body) and **full response** — each on a single line;
    run summary counts.
  - `DEBUG` — parsed row counts, resolved paths.
  - `WARN` — malformed rows skipped; retryable attempt failures; missing folders; non-2xx webhook responses.
  - `ERROR` — final failure after retries; missing webhook URL/secret; unexpected run exceptions (with stack).
  - The secret is never logged — only whether it is configured (the derived signature is logged).
  - Request/response bodies are logged on a single line (embedded newlines collapsed to spaces), so a
    multi-line/pretty-printed response is not split across log lines or shown with `_` newline placeholders.

---

## Verification

1. Build/deploy: `mvn -PautoInstallSinglePackage clean install` (or `-PautoInstallBundle -pl core` for
   code + redeploy `ui.config`).
2. Ensure CSVs exist in the input folder (run the CSV scheduler or drop sample CSVs).
3. **Conversion:** temporarily set the converter cron to every minute; confirm `…/json/<name>.json`
   appears per CSV with correct records/types; a malformed row logs `WARN` and the file still writes.
4. **Sending:** set `webhookUrl` (e.g. a https://webhook.site URL) and a `webhookSecret`; run the webhook
   scheduler; confirm each dataset arrives as a separate POST with `X-Workfront-Dataset` and
   `X-Workfront-Signature: sha256=<hex>`; recompute `HMAC-SHA256(secret, rawBody)` and confirm it matches.
5. **Resilience:** point `webhookUrl` at a failing URL; confirm per-file isolation, `maxAttempts` retries
   with backoff, ERROR logs, and that other files still send; fix the URL and confirm the next run succeeds.
6. `mvn -pl core test` runs the unit tests; the OSGi console shows the new services/schedulers `active`.

### Unit tests

| Test | Covers |
|---|---|
| `SimpleCsvParserTest` | RFC 4180 parsing — quoted fields, doubled quotes, embedded commas/newlines, CRLF/LF, missing trailing columns |
| `HmacUtilTest` | HMAC-SHA256 known-answer vector, lowercase-hex format, determinism |
| `WorkfrontJsonConverterServiceImplTest` | CSV → typed JSON (boolean `published`, int/null `daysForNextReview`), rows without a `Path` skipped |
| `WorkfrontWebhookServiceImplTest` | Signs + POSTs to an in-process HTTP server: exact body, `X-Workfront-Signature`/`X-Workfront-Dataset` headers, non-2xx → failure, refuses to send when URL/secret missing |
