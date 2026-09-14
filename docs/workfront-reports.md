# Workfront Reports Framework

Five governance reports over AEM pages. Each report queries pages by a rule, writes **one CSV per
brand** (NatWest / RBS / Ulster, …) into a segregated DAM folder, and runs **weekly (weekends)**.
**Reports 2–5** are converted to signed JSON and pushed to a **Workfront Fusion webhook**; **Report 1**
(All Live) is a full CSV kept in the DAM for download (not sent).

Built as a **common, reusable framework**: a shared report engine, a shared CSV→JSON converter, and a
shared signed-webhook sender.

### Webhook signature & security

Each dataset is POSTed to the configured Fusion webhook with:
- `Content-Type: application/json`
- `X-Workfront-Dataset: <reportId>-<brand>`
- `X-Workfront-Signature: sha256=<hex>` where the value is `HMAC-SHA256(webhookSecret, rawBody)` (see `HmacUtil`).

Fusion verifies by recomputing the HMAC over the exact received body with the shared secret. The
`webhookUrl` / `webhookSecret` live in the `WorkfrontWebhookServiceImpl` OSGi config — **set the secret
per environment; never commit a real secret**. The service refuses to send if the URL or secret is unset.

---

## The five reports

| # | reportId | Rule | Threshold (default) | Per brand | Sent |
|---|---|---|---|---|---|
| 1 | `all-live` | All **live** (published) pages under the configured paths | — | optional | No (DAM only) |
| 2 | `expiring-published` | **Published** pages expiring within N days (incl. already expired) | `thresholdDays` = 45 | Yes | Yes |
| 3 | `not-live-stale` | **Not live**, not modified in N months, no exclusion flag | `thresholdMonths` = 6 | Yes | Yes |
| 4 | `live-long-no-children` | **Live**, last published > N months ago, no child page, no exclusion flag | `thresholdMonths` = 18 | Yes | Yes |
| 5 | `archive-aged` | Pages in configured **archive folders** aged within [min,max] days | `archiveMinDays`=60, `archiveMaxDays`=90, `archiveDateProp`=`cq:lastModified` | Yes | Yes |

**Rule semantics** (`ReportFilterFactory`):

- *Live / published* — `ReplicationStatus.isActivated()` on `jcr:content`.
- *Last published* — `cq:lastReplicated`. *Last modified* — `cq:lastModified`. *Expiry* — `contentReviewExpiryDate`.
- *No child page* — the page has no child node of type `cq:Page`.
- *Exclusion* (applied to every report) — a page is skipped when it is under an **exclude path** or has an
  **exclude-property** match. Default exclude-property is `excludeFromReport = true`.

Reports 2–5 cap at **1000 rows per brand** (ordered by path). Report 1 is uncapped.

---

## Folder layout & naming

Sent reports root (scanned by the converter/webhook): `/content/dam/mysite/workfront-reports/`
```
workfront-reports/
  <reportId>/
    csv/   <reportId>-<brand>.csv     (written by the generator)
    json/  <reportId>-<brand>.json    (written by the converter; sent by the webhook)
```
Report 1 (DAM only, not scanned): `/content/dam/mysite/reports/all-live/csv/all-live-<brand>.csv`.

The dataset name in the JSON and the `X-Workfront-Dataset` header are the CSV base name
`<reportId>-<brand>` — so Workfront can identify both the report and the brand. Files are **overwritten**
each run (stable names → stable datasets).

---

## Configuration (authorable components)

One component per report (group **My Site - Structure**), all sharing a hidden base
(`mysite/components/reports/reportbase`) that renders the edit-mode summary + a **Generate now** button.

| Component (resource type) | Report |
|---|---|
| `mysite/components/reports/all-live` | All Live Pages |
| `mysite/components/reports/expiring-published` | Expiring Published |
| `mysite/components/reports/not-live-stale` | Not-Live Stale |
| `mysite/components/reports/live-long-no-children` | Live Long-Published, No Children |
| `mysite/components/reports/archive-aged` | Archive Aged |

**Dialog tabs** (per component): **Brands** (composite multifield: `brand` + `rootPaths`), **Rule**
(type-specific threshold fields), **Exclusions** (`excludePaths`, `excludeProps`), **Output**
(`outputFolder`, `maxRecords`, `activateCsv`), **Columns** (`header` + `source`). Defaults are applied by
`ReportDefinitionReader` for anything left empty, so a freshly-placed component already works.

**Columns** default to the 12-column governance schema (kept identical to the Workfront JSON converter):
`Hash,Title,Path,Brand,Last Modified,Modified By,Published,Next Review Date,Days For Next Review,Franchise,Page Owners,Template`.
Column `source` is a `jcr:content` property or a token: `:title`, `:path`, `:url`, `:hash`, `:brand`,
`:published`, `:daysToReview`.

---

## Schedulers (weekly)

| Scheduler | Default cron | Does |
|---|---|---|
| `ReportCsvGeneratorScheduler` (config.author) | `0 0 2 ? * SAT` | Discovers all report components and generates every per-brand CSV. |
| `WorkfrontJsonConverterScheduler` (config.author) | `0 0 3 ? * SAT` | Scans `reportsRoot`; converts each `<report>/csv/*.csv` → `<report>/json/`. |
| `WorkfrontWebhookScheduler` (config.author) | `0 0 4 ? * SAT` | Scans `reportsRoot`; POSTs each `<report>/json/*.json` to the webhook (HMAC-SHA256 signed). |

Each stage isolates failures per file/brand and (for the pipeline) retries in-run; anything still failing
is retried on the next run.

### Scheduler ↔ report mapping

The schedulers are **shared** across all reports (there is no per-report scheduler). A report is bound to
the pipeline purely by its **resource type** (for generation) and its **output folder under the reports
root** (for conversion + sending):

| Report (reportId) | Config resource type (discovered by the CSV scheduler) | Generated by | Output folder | Converted by | Sent by |
|---|---|---|---|---|---|
| `all-live` | `mysite/components/reports/all-live` | `ReportCsvGeneratorScheduler` | `/content/dam/mysite/reports/all-live` (outside reports root) | — (not converted) | — (DAM-only) |
| `expiring-published` | `mysite/components/reports/expiring-published` | `ReportCsvGeneratorScheduler` | `…/workfront-reports/expiring-published` | `WorkfrontJsonConverterScheduler` | `WorkfrontWebhookScheduler` |
| `not-live-stale` | `mysite/components/reports/not-live-stale` | `ReportCsvGeneratorScheduler` | `…/workfront-reports/not-live-stale` | `WorkfrontJsonConverterScheduler` | `WorkfrontWebhookScheduler` |
| `live-long-no-children` | `mysite/components/reports/live-long-no-children` | `ReportCsvGeneratorScheduler` | `…/workfront-reports/live-long-no-children` | `WorkfrontJsonConverterScheduler` | `WorkfrontWebhookScheduler` |
| `archive-aged` | `mysite/components/reports/archive-aged` | `ReportCsvGeneratorScheduler` | `…/workfront-reports/archive-aged` | `WorkfrontJsonConverterScheduler` | `WorkfrontWebhookScheduler` |

How the binding works:
- **Generation:** `ReportCsvGeneratorScheduler` → `ReportDefinitionReader.readAll(...)` runs one QueryBuilder
  query **per report resource type** under `searchRoot` (default `/content`), so every authored report
  component is found regardless of where its page lives. `all-live` writes outside the reports root, so it
  is never picked up by the converter/webhook (DAM-only).
- **Conversion & sending:** the converter and webhook schedulers are **folder-driven** — they iterate the
  child folders of `reportsRoot` (`/content/dam/mysite/workfront-reports`) and process each report's
  `csv/` → `json/` → webhook. To include or exclude a report from Workfront, place (or don't place) its
  output under the reports root.

So: **to add a 6th report**, create its component + resource type, point its `outputFolder` under the
reports root (to send it) or elsewhere (DAM-only) — no scheduler changes are needed.

---

## On-demand run

`POST /bin/mysite/report/run` with `configPath=<component path>` → `ReportRunServlet` reads the definition
and calls `ReportGeneratorService`, returning `{success, reportId, csvPaths[], rows}`. The **Generate now**
button on each component posts this using the author's session.

---

## Connectivity — file by file

```
Component (mysite/components/reports/<reportId>)
  └─ ReportType.fromResourceType(...)                          core/reports/ReportType.java
  └─ ReportDefinitionReader.readOne(component)                 core/reports/ReportDefinitionReader.java
       -> ReportDefinition (brands, thresholds, columns, …)    core/reports/ReportDefinition.java, BrandScope.java, ReportsConstants.java
  ReportCsvGeneratorScheduler (weekly)  ── or ── ReportRunServlet (/bin/mysite/report/run)
  └─ ReportGeneratorService.generate(def)                      core/services/impl/ReportGeneratorServiceImpl.java
       ├─ ReportFilterFactory.create(def, today)               core/reports/ReportFilterFactory.java (+ ReportFilter.java)
       ├─ shared utils: CsvSupport, PagePublicationUtil,       core/util/*.java
       │  BrandUtil, DateUtils, PageHashUtil
       └─ writes <outputFolder>/csv/<reportId>-<brand>.csv
  WorkfrontJsonConverterScheduler (weekly)
  └─ WorkfrontJsonConverterService.convert(csv, <report>/json) core/services/impl/WorkfrontJsonConverterServiceImpl.java
       └─ writes <report>/json/<reportId>-<brand>.json         (dataset = <reportId>-<brand>)
  WorkfrontWebhookScheduler (weekly)
  └─ WorkfrontWebhookService.send(json)                        core/services/impl/WorkfrontWebhookServiceImpl.java
       └─ POST <webhookUrl>  X-Workfront-Signature: sha256=…   HMAC over the body (HmacUtil)
                             X-Workfront-Dataset: <reportId>-<brand>
```

**OSGi configs** (`ui.config/.../osgiconfig/`): `config/…ReportGeneratorServiceImpl.cfg.json`
(pageBatchSize); `config.author/…ReportCsvGeneratorScheduler`, `…WorkfrontJsonConverterScheduler`,
`…WorkfrontWebhookScheduler` (crons + reportsRoot); `config/…WorkfrontWebhookServiceImpl.cfg.json`
(webhook URL + secret — set per environment, never commit real secrets).

**Service user / ACLs** (`RepositoryInitializer~workfront.cfg.json`): reuses `workfront-csv-service`
(subservice `workfront-csv-write`); read on `/content`, read/write/replicate on
`/content/dam/mysite/workfront-reports` and `/content/dam/mysite/reports/all-live`. **Vault filter**
(`ui.content`) includes both DAM roots.

---

## Logging

`com.mysite.core.reports` / `.services` / `.schedulers` (routed to `csv-generator.log`):
- **INFO** — activate config, run start/finish, per report/brand row counts, run summary.
- **DEBUG** — batch counts, cap reached, resolved paths.
- **WARN** — missing roots/folders, skipped malformed rows, retryable failures, non-2xx webhook.
- **ERROR** — final failures (with cause), missing/invalid config. The webhook secret is never logged.

---

## Tests

`mvn -pl core test` (JUnit 5 + AEM Mock):

| Test | Covers |
|---|---|
| `ReportTypeTest` | reportId ↔ resource type, default folders, cap, sent flag |
| `ReportDefinitionReaderTest` | brands, thresholds, columns, defaults, all-live specifics |
| `ReportFilterFactoryTest` | not-live-stale + archive-aged date rules |
| `ReportGeneratorServiceImplTest` | column resolution (incl. `:brand`/`:published`/`:daysToReview`) + exclusion (path/property) |
| `ReportConfigModelImplTest` | edit-mode model: validity, rule summary, definition |
| `ReportRunServletTest` | run endpoint: success + 400/404 guards |
| `CsvSupportTest`, `BrandUtilTest`, `DateUtilsTest`, `PagePublicationUtilTest` | shared utilities |

The published-page rules (all-live / expiring / live-long) are verified end-to-end at deploy time,
since AEM Mock reports pages as not-activated.

---

## Notes / current scope

- **Columns are shared** across all reports for now (the governance schema); change per component later.
  The JSON converter maps that fixed schema to typed JSON — if you change columns, revisit the converter.
- **Report 4 “assign to user”** is a Workfront-side action; AEM only delivers the dataset.
- **Report 5** is report-only (AEM does not move pages to the archive folders).
- **Brands → roots** are configured explicitly per report; each brand yields one CSV.

