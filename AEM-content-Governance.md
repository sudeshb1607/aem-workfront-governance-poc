# AEM Content Governance Utilities

Two automated CSV-report utilities are shipped in this codebase to support content governance at scale. Both share the same architecture: an **AEM authoring component** holds the configuration, a **cron-driven scheduler** discovers all configurations across the repository and generates one report per configuration, a **generator service** performs batched page traversal and writes the result to the DAM, and a **service user** (repoinit-managed) provides the required JCR write permissions.

---

## 1. Workfront Dashboard CSV Export

Generates page-metadata CSV files into the DAM so that Adobe Workfront dashboards can consume them as data sources. One CSV is generated per configured content tree. A second self-healing scheduler detects and repairs missing, empty, or stale files.

### How It Works End-to-End

```
┌─────────────────────────────┐
│  Author dialog               │  Author sets content trees and CSV names on a
│  (workfront-dashboard-config)│  configuration component dropped on any page.
└────────────┬────────────────┘
             │  JCR content saved as composite multifield nodes
             ▼
┌─────────────────────────────┐
│  WorkfrontCsvScheduler       │  Runs every Saturday at 10:00 AM (configurable).
│  (Quartz cron)               │  Queries all config components via QueryBuilder,
└────────────┬────────────────┘  reads their trees, calls the generator service.
             │
             ▼
┌─────────────────────────────┐
│  WorkfrontCsvGeneratorService│  For each content tree:
│  (OSGi service)              │  1. Queries cq:Page nodes in batches of 500.
└────────────┬────────────────┘  2. Reads jcr:title, path, cq:lastModified,
             │                      cq:template; computes a URL hash.
             │                   3. Assembles RFC-4180 CSV in memory.
             │                   4. Writes asset to DAM via AssetManager.
             │                   5. Activates (replicates) the asset.
             ▼
┌─────────────────────────────┐
│  DAM asset                   │  /content/dam/mysite/workfront-dashboard/<name>.csv
└─────────────────────────────┘
             │
             ▼
┌─────────────────────────────┐
│  WorkfrontCsvVerification    │  Runs every Sunday at 08:00 AM.
│  Scheduler                   │  Checks each expected CSV: missing / empty /
└─────────────────────────────┘  stale (older than freshness threshold).
                                  Re-generates unhealthy files automatically.
```

On any failure, an AEM Inbox notification is raised to the `administrators` group. Failures on one tree never prevent the remaining trees from processing.

### CSV Output Format

| Column | Source |
|---|---|
| Hash | SHA-256 of `<path>.html` (stable, unique Workfront identifier) |
| Title | `jcr:content/jcr:title` (falls back to node name) |
| Path | Page JCR path |
| Last Modified | `jcr:content/cq:lastModified` |
| Template | `jcr:content/cq:template` |

Values are RFC-4180 compliant (commas, quotes, and newlines are quoted; embedded quotes are doubled). Line endings are `\r\n`.

### Authoring — Component Dialog

Add the `workfront-dashboard-config` component to any page (typically an operations or admin page). The dialog has one tab:

**Configuration tab**

| Field | Description | Required |
|---|---|---|
| Content Trees (multifield) | — | — |
| — Content Tree Path | Root path of the content tree to export (e.g. `/content/mysite/us/en`). Scanned including the root page itself. | Yes |
| — CSV File Name | Output file name without extension (e.g. `us-en-pages` → writes `us-en-pages.csv`). | Yes |

Multiple content trees can be added. Each produces a separate CSV file in the DAM.

### OSGi Configurations

**`WorkfrontCsvScheduler`** — `com.mysite.core.schedulers.WorkfrontCsvScheduler.cfg.json`

| Property | Default | Description |
|---|---|---|
| `scheduler.expression` | `0 0 10 ? * SAT` | Quartz cron: every Saturday at 10:00 AM |
| `scheduler.concurrent` | `false` | Prevents overlapping runs |
| `searchRoot` | `/content` | Repository root scanned for config components |
| `pauseBetweenTreesSeconds` | `5` | CPU cool-down between each tree export |

**`WorkfrontCsvVerificationScheduler`** — `com.mysite.core.schedulers.WorkfrontCsvVerificationScheduler.cfg.json`

| Property | Default | Description |
|---|---|---|
| `scheduler.expression` | `0 0 8 ? * SUN` | Every Sunday at 08:00 AM |
| `scheduler.concurrent` | `false` | — |
| `searchRoot` | `/content` | — |
| `freshnessThresholdMinutes` | `60` | CSVs older than this (in minutes) are re-generated. Set to `0` to disable staleness check. |
| `minCsvSizeBytes` | `1` | CSVs smaller than this are treated as empty and re-generated |
| `pauseBetweenTreesSeconds` | `5` | — |

**`WorkfrontCsvGeneratorServiceImpl`** — `com.mysite.core.services.impl.WorkfrontCsvGeneratorServiceImpl.cfg.json`

| Property | Default | Description |
|---|---|---|
| `damRootPath` | `/content/dam/mysite/workfront-dashboard` | DAM folder all CSVs are written into |
| `pageBatchSize` | `500` | Pages per QueryBuilder batch; keeps heap flat on large trees |

### Files Involved

| Layer | File |
|---|---|
| **Component UI** | `ui.apps/.../components/workfront-dashboard-config/.content.xml` |
| | `ui.apps/.../components/workfront-dashboard-config/_cq_editConfig.xml` |
| | `ui.apps/.../components/workfront-dashboard-config/_cq_dialog/.content.xml` |
| | `ui.apps/.../components/workfront-dashboard-config/workfront-dashboard-config.html` |
| **Java — Service** | `core/.../services/WorkfrontCsvGeneratorService.java` (interface + `CsvGenerationResult`) |
| | `core/.../services/impl/WorkfrontCsvGeneratorServiceImpl.java` |
| **Java — Schedulers** | `core/.../schedulers/WorkfrontCsvScheduler.java` |
| | `core/.../schedulers/WorkfrontCsvVerificationScheduler.java` |
| | `core/.../schedulers/WorkfrontInboxNotifier.java` |
| | `core/.../schedulers/SchedulerSupport.java` |
| **Java — Models/Config** | `core/.../models/WorkfrontDashboardConfigModel.java` |
| | `core/.../models/impl/WorkfrontDashboardConfigModelImpl.java` |
| | `core/.../models/workfront/ContentTreeConfig.java` |
| | `core/.../models/workfront/WorkfrontConfigReader.java` |
| | `core/.../models/workfront/WorkfrontConfigConstants.java` |
| | `core/.../models/workfront/package-info.java` |
| **OSGi Config** | `ui.config/.../osgiconfig/config/com.mysite.core.schedulers.WorkfrontCsvScheduler.cfg.json` |
| | `ui.config/.../osgiconfig/config/com.mysite.core.schedulers.WorkfrontCsvVerificationScheduler.cfg.json` |
| | `ui.config/.../osgiconfig/config/com.mysite.core.services.impl.WorkfrontCsvGeneratorServiceImpl.cfg.json` |
| | `ui.config/.../osgiconfig/config/org.apache.sling.serviceusermapping...~workfront.cfg.json` |
| | `ui.config/.../osgiconfig/config/org.apache.sling.jcr.repoinit...~workfront.cfg.json` |
| **Docs** | `docs/workfront-csv-export.md` |

### Security & Service User

A dedicated system user `workfront-csv-service` is created by the repoinit configuration. It is granted:

- Read access under `/content` (page traversal)
- Read/write access under `/content/dam/mysite/workfront-dashboard` (CSV asset creation)
- Read/write access under `/var/workflow/instances` (Inbox notification tasks)

The subservice name mapping is `com.mysite.mysite.core:workfront-csv-write` → `workfront-csv-service`.

---

## 2. Unpublished Pages Report

Scans one or more content trees and produces a CSV listing every page that has never been published, or whose last publication is older than a configured number of days. The report is written to the DAM and optionally activated. Unlike the Workfront utility, this one also supports **on-demand generation** via a POST servlet, so authors can trigger a run immediately from the component's edit view.

### How It Works End-to-End

```
┌────────────────────────────────┐
│  Author dialog                  │  Author configures: scan roots, exclude
│  (unpublishedpagesreport)       │  paths, exclude-property rules, threshold
└──────────────┬─────────────────┘  days, output folder, CSV columns.
               │  JCR content saved
               ▼
   ┌──────────────────────────────────────────────────────────────┐
   │  Two trigger paths                                            │
   │                                                              │
   │  A) SCHEDULED: UnpublishedPagesReportScheduler               │
   │     Runs on the 1st of every month at 02:00.                 │
   │     Queries all config components, reads configs, calls       │
   │     the generator service for each.                          │
   │                                                              │
   │  B) ON-DEMAND: UnpublishedReportRunServlet (POST)            │
   │     POST /bin/mysite/unpublishedreport?configPath=<path>     │
   │     Reads a single config component and calls the service.   │
   └──────────────┬───────────────────────────────────────────────┘
                  │
                  ▼
┌────────────────────────────────┐
│  UnpublishedPagesReportService  │  For each configured scan root:
│  (OSGi service)                 │  1. Queries cq:Page nodes in batches of 500.
└──────────────┬─────────────────┘  2. Applies path exclusions.
               │                    3. Applies exclude-property conditions
               │                       (checked on jcr:content, then page node).
               │                    4. Checks publication state:
               │                       — cq:lastReplicated is absent → unpublished
               │                       — cq:lastReplicationAction ≠ "Activate" → unpublished
               │                       — last activation older than threshold → unpublished
               │                    5. Maps each qualifying page to configured columns.
               │                    6. Assembles RFC-4180 CSV in memory.
               │                    7. Writes asset to DAM (date suffix appended to name).
               │                    8. Optionally activates the asset.
               ▼
┌────────────────────────────────┐
│  DAM asset                      │  /content/dam/mysite/reports/unpublished-pages-20250801.csv
└────────────────────────────────┘
```

On any failure the scheduler raises an AEM Inbox notification. The on-demand servlet returns a JSON response with `success`, `csvPath`, and `rowsReported` fields.

### Publication State Logic

A page is included in the report when **any** of these is true:

1. `jcr:content/cq:lastReplicated` is absent (never published)
2. `jcr:content/cq:lastReplicationAction` is not `"Activate"` (last action was Deactivate or Delete)
3. `jcr:content/cq:lastReplicated` is present but older than `thresholdDays` days

### Column Source Tokens

The columns are fully configurable. Each column has a **Header** (CSV heading) and a **Source** (either a `jcr:content` property name or one of four special tokens):

| Token | Resolves To |
|---|---|
| `:title` | `jcr:content/jcr:title` (falls back to node name) |
| `:path` | Page JCR path |
| `:url` | Publish URL via Externalizer (falls back to `<path>.html`) |
| `:hash` | SHA-256 hash of the publish URL (stable Workfront identifier) |

Any other source value is treated as a `jcr:content` property name. `Calendar` and `Date` values are formatted as ISO-8601. Multi-value properties are joined with `;`.

When no columns are configured, the service falls back to a built-in default set.

### Authoring — Component Dialog

Add the `unpublishedpagesreport` component to any page. The dialog has four tabs:

**Scope tab**

| Field | Description | Default |
|---|---|---|
| Scan Roots (multifield) | Content roots to scan for `cq:Page` nodes | `/content` |
| Exclude Paths (multifield) | Path prefixes whose pages are omitted from the report | `/content/test` |
| Exclude-property Conditions (multifield) | — | — |
| — Property Name | `jcr:content` or page-node property to test (e.g. `excludeFromDelete`) | `excludeFromDelete` |
| — Property Value | Value that causes the page to be excluded (e.g. `true`) | `true` |

**Rule tab**

| Field | Description | Default |
|---|---|---|
| Not-published Threshold (days) | Pages not activated within this window are reported. Minimum: 1. | `90` |

**Output tab**

| Field | Description | Default |
|---|---|---|
| CSV Output Folder | DAM folder the CSV is written into | `/content/dam/mysite/reports` |
| CSV Base File Name | File name base; today's date (`yyyyMMdd`) is appended | `unpublished-pages` |
| Activate CSV after generation | Whether to replicate the asset to publish | checked (`true`) |

**Columns tab**

| Field | Description |
|---|---|
| Columns (multifield) | — |
| — Header | CSV column heading |
| — Source | jcr:content property name or `:title` / `:path` / `:url` / `:hash` token |

### On-Demand Servlet

```
POST /bin/mysite/unpublishedreport
Parameter: configPath=<JCR path to the configuration component>
```

The servlet verifies the resource exists and has the correct resource type before calling the service. Response (JSON):

```json
{ "success": true, "csvPath": "/content/dam/mysite/reports/unpublished-pages-20250801.csv", "rowsReported": 47 }
```

On failure: `HTTP 500` with `"success": false` and an `"error"` field.

### OSGi Configurations

**`UnpublishedPagesReportScheduler`** — `com.mysite.core.schedulers.UnpublishedPagesReportScheduler.cfg.json`

| Property | Default | Description |
|---|---|---|
| `scheduler.expression` | `0 0 2 1 * ?` | Quartz cron: 1st of every month at 02:00 AM |
| `scheduler.concurrent` | `false` | Prevents overlapping runs |
| `searchRoot` | `/content` | Repository root scanned for config components |
| `pauseBetweenConfigsSeconds` | `5` | CPU cool-down between each configuration run |

**`UnpublishedPagesReportServiceImpl`** — (no separate cfg.json; configured via the `@ObjectClassDefinition` on the class)

| Property | Default | Description |
|---|---|---|
| `pageBatchSize` | `500` | Pages per QueryBuilder batch |

### Files Involved

| Layer | File |
|---|---|
| **Component UI** | `ui.apps/.../components/unpublishedpagesreport/.content.xml` |
| | `ui.apps/.../components/unpublishedpagesreport/_cq_editConfig.xml` |
| | `ui.apps/.../components/unpublishedpagesreport/_cq_dialog/.content.xml` |
| | `ui.apps/.../components/unpublishedpagesreport/unpublishedpagesreport.html` |
| **Java — Service** | `core/.../services/UnpublishedPagesReportService.java` (interface + `ReportResult`) |
| | `core/.../services/impl/UnpublishedPagesReportServiceImpl.java` |
| **Java — Servlet** | `core/.../servlets/UnpublishedReportRunServlet.java` |
| **Java — Scheduler** | `core/.../schedulers/UnpublishedPagesReportScheduler.java` |
| **Java — Models/Config** | `core/.../models/UnpublishedReportConfigModel.java` |
| | `core/.../models/impl/UnpublishedReportConfigModelImpl.java` |
| | `core/.../models/report/UnpublishedReportConfig.java` |
| | `core/.../models/report/UnpublishedReportConfigReader.java` |
| | `core/.../models/report/ReportConfigConstants.java` |
| | `core/.../models/report/ReportColumn.java` |
| | `core/.../models/report/ExcludeProperty.java` |
| | `core/.../models/report/package-info.java` |
| **Shared** | `core/.../schedulers/WorkfrontInboxNotifier.java` (shared with Workfront utility) |
| | `core/.../schedulers/SchedulerSupport.java` (shared) |
| | `core/.../util/PageHashUtil.java` (shared hash utility) |
| **OSGi Config** | `ui.config/.../osgiconfig/config/com.mysite.core.schedulers.UnpublishedPagesReportScheduler.cfg.json` |
| | `ui.config/.../osgiconfig/config/org.apache.sling.serviceusermapping...~unpublishedreport.cfg.json` |
| | `ui.config/.../osgiconfig/config/org.apache.sling.jcr.repoinit...~unpublishedreport.cfg.json` |
| **Docs** | `docs/unpublished-pages-report.md` |

### Security & Service User

A dedicated system user `unpublished-report-service` is created by the repoinit configuration. It is granted:

- Read access under `/content` (page traversal)
- Read/write access under the configured DAM output folder (CSV asset creation)
- Read/write access under `/var/workflow/instances` (Inbox notifications)

The subservice name mapping is `com.mysite.mysite.core:unpublished-report-write` → `unpublished-report-service`.

---

## Shared Infrastructure

Both utilities reuse the following shared classes:

| Class | Purpose |
|---|---|
| `WorkfrontInboxNotifier` | Creates a Granite task (AEM Inbox item) for the `administrators` group on any failure |
| `SchedulerSupport` | `pause(seconds)` — interruptible CPU cool-down sleep between processing units |
| `PageHashUtil` | SHA-256 hash of a URL string, formatted as a lowercase hex string; used as a stable, unique page identifier |

Both services use the same **batched QueryBuilder pattern**: pages are fetched `pageBatchSize` at a time via `p.offset` / `p.limit`, so only one batch is in heap at any time. This keeps memory consumption flat regardless of content tree size (validated to ~10,000 pages per tree). For trees beyond ~50,000 pages, switch to a streaming query with file-backed write (see the individual docs for guidance).

Both services write assets using `AssetManager.createAsset(path, stream, mimeType, true)` with the `true` flag meaning overwrite-if-exists. Replication failures are non-fatal in both cases — the asset is always preserved on author.
