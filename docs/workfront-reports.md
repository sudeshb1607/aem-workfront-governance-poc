# Workfront Reports Framework

Five governance reports over AEM pages. Each report queries pages by its own rule, writes **one CSV per
brand** (e.g. NatWest / RBS / Ulster) into a segregated DAM folder, and runs **weekly**. **Reports 2–5**
are converted to signed JSON and pushed to a **Workfront Fusion webhook**; **Report 1** (All Live) is a
full CSV kept in the DAM for download (not sent).

It is a **common, reusable framework**: one shared report engine, one shared CSV→JSON converter, and one
shared signed-webhook sender. Each report's *selection rule* lives in its **own class** (`reports/filter/*`)
so a change to one report cannot affect the others.

---

## 1. Architecture & flow

### 1a. CSV generation (weekly)

```
(cron, weekend)  ReportCsvGeneratorScheduler          [config.author]
      │   opens service resolver → user 'workfront-csv-service' (subservice 'workfront-csv-write')
      ▼
ReportDefinitionReader.readAll("/content")
      │   one QueryBuilder query per report resource type  →  finds every authored report component
      ▼
for each ReportDefinition:
   ReportGeneratorService.generate(def)
      └── for each configured brand:
             QueryBuilder batches:  type=cq:Page under the brand's root path(s)
                └── ReportFilter (per-report class)  +  exclusion (path / property)
                       └── matched page  →  resolve columns  →  CSV row   (stop at maxRecords)
             write  <outputFolder>/csv/<reportId>-<brand>.csv   via AssetManager
```

### 1b. Convert & push to Workfront (weekly)

```
DAM  /content/dam/mysite/workfront-reports/<reportId>/csv/<reportId>-<brand>.csv
      │  (cron, weekend)  WorkfrontJsonConverterScheduler  — scans the reports root  [config.author]
      ▼
WorkfrontJsonConverterService.convert(csv → <reportId>/json)
      │  SimpleCsvParser → typed JSON dataset { dataset, generatedAt, recordCount, records[] }
      ▼
DAM  /content/dam/mysite/workfront-reports/<reportId>/json/<reportId>-<brand>.json
      │  (cron, weekend)  WorkfrontWebhookScheduler  — scans the reports root  [config.author]
      ▼
WorkfrontWebhookService.send(json)
      │  signature = HMAC-SHA256(webhookSecret, rawBody)   (HmacUtil)
      ▼
POST <webhookUrl>
      Content-Type: application/json
      X-Workfront-Dataset:   <reportId>-<brand>
      X-Workfront-Signature: sha256=<hex>
   →  Workfront Fusion  (recomputes the HMAC to verify, then ingests the dataset)
```

Report 1 (`all-live`) writes **outside** the reports root, so the converter/webhook never pick it up — it
stays a DAM-only CSV.

---

## 2. The five reports

| # | reportId | Rule | Threshold (default) | Per brand | Sent |
|---|---|---|---|---|---|
| 1 | `all-live` | All **live** (published) pages under the configured paths | — | optional | No (DAM only) |
| 2 | `expiring-published` | **Published** pages expiring within N days (incl. already expired) | `thresholdDays` = 45 | Yes | Yes |
| 3 | `not-live-stale` | **Not live**, not modified in N months | `thresholdMonths` = 6, `staleDateProp` = `cq:lastModified` | Yes | Yes |
| 4 | `live-long-no-children` | **Live**, last published > N months ago, no child page | `thresholdMonths` = 18 | Yes | Yes |
| 5 | `archive-aged` | Pages in configured **archive folders** aged within [min,max] days | `archiveMinDays`=60, `archiveMaxDays`=90, `archiveDateProp`=`cq:lastModified` | Yes | Yes |

**Rule classes (segregated)** — `reports/filter/`:

| Report | Filter class | Key checks |
|---|---|---|
| all-live | `AllLiveFilter` | `ReplicationStatus.isActivated()` |
| expiring-published | `ExpiringPublishedFilter` | published AND `contentReviewExpiryDate` ≤ today+N |
| not-live-stale | `NotLiveStaleFilter` | not published AND `<staleDateProp>` < today−N months |
| live-long-no-children | `LiveLongNoChildrenFilter` | published AND `cq:lastReplicated` < today−N months AND no child `cq:Page` |
| archive-aged | `ArchiveAgedFilter` | `<archiveDateProp>` age ∈ [min,max] days |

`ReportFilterFactory` only *dispatches* a definition to the right class — editing one report's rule touches
only its filter class.

**Exclusion** (applied to every report, in the generator — not the filter): a page is skipped when it is
under an **exclude path**, or when the **single optional exclude property** is set and the page's property
(read from `jcr:content`, then the page node) equals the configured boolean value. **If the exclude
property name is empty, no property comparison is made.**

Reports 2–5 cap at **`maxRecords` (default 1000) per brand** (ordered by path). Report 1 is uncapped.

---

## 3. Folder layout & naming

Sent reports root (scanned by the converter/webhook): `/content/dam/mysite/workfront-reports/`
```
workfront-reports/
  <reportId>/
    csv/   <reportId>-<brand>.csv     (written by the generator)
    json/  <reportId>-<brand>.json    (written by the converter; sent by the webhook)
```
Report 1 (DAM only, not scanned): `/content/dam/mysite/reports/all-live/csv/all-live-<brand>.csv`.

The JSON `dataset` field **and** the `X-Workfront-Dataset` header are the CSV base name
`<reportId>-<brand>` — so Workfront can identify both the report and the brand. Files are **overwritten**
each run (stable names → stable datasets).

---

## 4. Developer setup / prerequisites

Everything below is deployed automatically by the Maven build (`ui.config`, `ui.apps`), except the
**webhook URL/secret**, which must be set per environment.

### 4.1 System user, service mapping & ACLs (auto, via repoinit)
- **System user:** `workfront-csv-service` at `system/mysite`.
- **Subservice mapping:** `com.mysite.mysite.core:workfront-csv-write=workfront-csv-service`
  — `ui.config/.../config/org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended~workfront.cfg.json`.
- **Repoinit** — `ui.config/.../config/org.apache.sling.jcr.repoinit.RepositoryInitializer~mysitereports.cfg.json`
  creates the user, the DAM folders, `/var/taskmanagement`, and grants:
  - `jcr:read` on `/content`
  - `jcr:read, rep:write, jcr:versionManagement, crx:replicate` on `/content/dam/mysite/workfront-reports`
    and `/content/dam/mysite/reports/all-live`
  - `jcr:read, rep:write` on `/var/taskmanagement` (AEM Inbox failure notifications).

### 4.2 DAM folders (auto, via repoinit)
- `/content/dam/mysite/workfront-reports` — sent reports (2–5).
- `/content/dam/mysite/reports/all-live` — report 1 (DAM-only).
- Vault filter for both is in `ui.content/.../META-INF/vault/filter.xml`.

### 4.3 OSGi configuration (PIDs)
| PID (location) | Set |
|---|---|
| `com.mysite.core.services.impl.WorkfrontWebhookServiceImpl` (`config/`) | **`webhookUrl`, `webhookSecret`** (per environment — never commit a real secret), plus header names + timeouts |
| `com.mysite.core.services.impl.ReportGeneratorServiceImpl` (`config/`) | `pageBatchSize` (default 500) |
| `com.mysite.core.schedulers.ReportCsvGeneratorScheduler` (`config.author/`) | cron (`0 0 2 ? * SAT`), `searchRoot`, pause |
| `com.mysite.core.schedulers.WorkfrontJsonConverterScheduler` (`config.author/`) | cron (`0 0 3 ? * SAT`), `reportsRoot`, retries |
| `com.mysite.core.schedulers.WorkfrontWebhookScheduler` (`config.author/`) | cron (`0 0 4 ? * SAT`), `reportsRoot`, retries |

The schedulers live in `config.author` so they run on **author only** (where the content + DAM live).

### 4.4 Build & deploy
```
mvn -PautoInstallSinglePackage clean install      # full package to a local author
# or, iteratively:
mvn -PautoInstallBundle install -pl core          # code only
mvn -PautoInstallPackage install -pl ui.apps      # components
mvn -PautoInstallPackage install -pl ui.config    # OSGi configs / repoinit
```

### 4.5 Authoring a report (per report component)
1. Open a config page under a site root (any `cq:Page` under `/content`), e.g.
   `/content/mysite/us/en/workfront-report-config`.
2. From the component browser (**My Site - Structure**) add the report component(s).
3. Configure the dialog tabs (below) and click **Generate now** to test, or wait for the weekly cron.

---

## 5. Configuration (authorable components)

One component per report (group **My Site - Structure**), all sharing a hidden base
(`mysite/components/reports/reportbase`) that renders the edit-mode summary + a **Generate now** button.

| Component (resource type) | Report |
|---|---|
| `mysite/components/reports/all-live` | All Live Pages |
| `mysite/components/reports/expiring-published` | Expiring Published |
| `mysite/components/reports/not-live-stale` | Not-Live Stale |
| `mysite/components/reports/live-long-no-children` | Live Long-Published, No Children |
| `mysite/components/reports/archive-aged` | Archive Aged |

**Dialog tabs:**
- **Brands** — composite multifield: `brand` (key) + `rootPaths` (one or more content roots). One CSV per brand.
- **Rule** — the type-specific threshold field(s) (e.g. `thresholdDays`, `thresholdMonths` + `staleDateProp`,
  or `archiveMinDays`/`archiveMaxDays`/`archiveDateProp`).
- **Exclusions** — `excludePaths` (multifield) + a **single** `excludePropertyName` (text) and
  `excludePropertyValue` (select: `true`/`false`). Leave the name empty for no property exclusion.
- **Output** — `outputFolder`, `maxRecords`, `activateCsv`.
- **Columns** — `header` + `source` rows.

Defaults are applied by `ReportDefinitionReader` for anything left empty, so a freshly-placed component
already works.

**Columns** default to the governance schema (kept identical to the JSON converter's expected header):
`Hash,Title,Path,Brand,Last Modified,Modified By,Published,Next Review Date,Days For Next Review,Franchise,Page Owners,Template`.
Column `source` is a `jcr:content` property or a token: `:title`, `:path`, `:url`, `:hash`, `:brand`,
`:published`, `:daysToReview`.

---

## 6. Schedulers & scheduler ↔ report mapping

| Scheduler (config.author) | Default cron | Does |
|---|---|---|
| `ReportCsvGeneratorScheduler` | `0 0 2 ? * SAT` | Discovers all report components and generates every per-brand CSV. |
| `WorkfrontJsonConverterScheduler` | `0 0 3 ? * SAT` | Scans `reportsRoot`; converts each `<report>/csv/*.csv` → `<report>/json/`. |
| `WorkfrontWebhookScheduler` | `0 0 4 ? * SAT` | Scans `reportsRoot`; POSTs each `<report>/json/*.json` (HMAC-SHA256 signed). |

The schedulers are **shared** across all reports (no per-report scheduler). A report binds to the pipeline
by its **resource type** (generation) and its **output folder under the reports root** (conversion + send):

| reportId | Config resource type | Generated by | Output folder | Converted / Sent by |
|---|---|---|---|---|
| `all-live` | `…/reports/all-live` | CSV scheduler | `/content/dam/mysite/reports/all-live` (outside root) | — (DAM-only) |
| `expiring-published` | `…/reports/expiring-published` | CSV scheduler | `…/workfront-reports/expiring-published` | Converter + Webhook |
| `not-live-stale` | `…/reports/not-live-stale` | CSV scheduler | `…/workfront-reports/not-live-stale` | Converter + Webhook |
| `live-long-no-children` | `…/reports/live-long-no-children` | CSV scheduler | `…/workfront-reports/live-long-no-children` | Converter + Webhook |
| `archive-aged` | `…/reports/archive-aged` | CSV scheduler | `…/workfront-reports/archive-aged` | Converter + Webhook |

Each stage isolates failures per file/brand and retries in-run; anything still failing is retried next run.

---

## 7. On-demand run

`POST /bin/mysite/report/run` with `configPath=<component path>` → `ReportRunServlet` reads the definition
and calls `ReportGeneratorService`, returning `{success, reportId, csvPaths[], rows}`. The **Generate now**
button posts this using the author's session.

---

## 8. Connectivity — file by file

```
Component (mysite/components/reports/<reportId>)
  └─ ReportType.fromResourceType(...)                          core/reports/ReportType.java
  └─ ReportDefinitionReader.readOne(component)                 core/reports/ReportDefinitionReader.java
       -> ReportDefinition (brands, thresholds, columns, …)    core/reports/{ReportDefinition,BrandScope,ReportsConstants}.java
  ReportCsvGeneratorScheduler (weekly)  ── or ── ReportRunServlet (/bin/mysite/report/run)
  └─ ReportGeneratorService.generate(def)                      core/services/impl/ReportGeneratorServiceImpl.java
       ├─ ReportFilterFactory.create(def, today)               core/reports/ReportFilterFactory.java
       │     -> AllLiveFilter / ExpiringPublishedFilter /      core/reports/filter/*.java
       │        NotLiveStaleFilter / LiveLongNoChildrenFilter / ArchiveAgedFilter
       ├─ shared utils                                          core/util/{CsvSupport,PagePublicationUtil,BrandUtil,DateUtils,PageHashUtil}.java
       └─ writes <outputFolder>/csv/<reportId>-<brand>.csv
  WorkfrontJsonConverterScheduler (weekly)
  └─ WorkfrontJsonConverterService.convert(csv, <report>/json) core/services/impl/WorkfrontJsonConverterServiceImpl.java
       └─ SimpleCsvParser (core/util) → writes <report>/json/<reportId>-<brand>.json
  WorkfrontWebhookScheduler (weekly)
  └─ WorkfrontWebhookService.send(json)                        core/services/impl/WorkfrontWebhookServiceImpl.java
       └─ HmacUtil (core/util) → POST <webhookUrl> with X-Workfront-Signature + X-Workfront-Dataset
```

---

## 9. Logging

`com.mysite.core.reports` / `.services` / `.schedulers` (routed to `csv-generator.log`):
- **INFO** — activate config, run start/finish, per report/brand row counts, run summary, webhook status.
- **DEBUG** — batch counts, cap reached, resolved paths.
- **WARN** — missing roots/folders, skipped malformed rows, retryable failures, non-2xx webhook.
- **ERROR** — final failures (with cause), missing/invalid config. The webhook secret is never logged.

---

## 10. Tests

`mvn -pl core test` (JUnit 5 + AEM Mock + Mockito):

| Test | Covers |
|---|---|
| `ReportTypeTest` | reportId ↔ resource type, default folders, cap, sent flag |
| `ReportDefinitionReaderTest` | brands, thresholds, columns, single exclude property, defaults |
| `ReportFilterFactoryTest` | all five rules (published rules via mocked `ReplicationStatus`) |
| `ReportGeneratorServiceImplTest` | full generate() flow, columns, exclusion, cap, pagination, isolation |
| `ReportConfigModelImplTest` | edit-mode model: validity, rule summary, definition |
| `ReportRunServletTest` | run endpoint: success + failure + guards |
| `WorkfrontJsonConverterServiceImplTest` | CSV→typed JSON + DAM convert path |
| `WorkfrontWebhookServiceImplTest` | signing + POST (in-process server), read/connect failures |
| scheduler tests | root-driven iteration, retries, isolation |
| `CsvSupportTest`, `BrandUtilTest`, `DateUtilsTest`, `PagePublicationUtilTest`, `HmacUtilTest`, `SimpleCsvParserTest` | shared utilities |

---

## 11. Extending — add a 6th report

1. Add a value to `ReportType` (reportId, output default, sent flag, default cap).
2. Add a filter class in `reports/filter/` and wire it in `ReportFilterFactory`.
3. Add an authorable component `mysite/components/reports/<reportId>` (extend `reportbase`) with its dialog.
4. Point its `outputFolder` under the reports root to send it, or elsewhere for DAM-only.

No scheduler changes are required — discovery is by resource type, and conversion/sending are folder-driven.

---

## 12. Notes / current scope

- **Columns are shared** across all reports for now (the governance schema); change per component later.
  The JSON converter maps that fixed schema to typed JSON — if you change columns, revisit the converter.
- **Report 4 “assign to user”** is a Workfront-side action; AEM only delivers the dataset.
- **Report 5** is report-only (AEM does not move pages to the archive folders).
- **Brands → roots** are configured explicitly per report; each brand yields one CSV.
- `cq:lastModified` is auto-stamped by AEM on write, so `not-live-stale` / `archive-aged` support a
  configurable date property (`staleDateProp` / `archiveDateProp`) when a stable/backdated date is needed.
