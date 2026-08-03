# Unpublished Pages Report

Generates a monthly CSV report of `cq:Page`s under `/content` that have **not been published
within the last N days** (default 90), writes it into the DAM, and activates it. Every aspect —
scan roots, excluded paths, exclude-by-property conditions, threshold, output location, and the
CSV columns — is **author-configurable** through a component dialog using multifields. Built for
AEM 6.5 (AMS), self-isolating per configuration, and observable via the AEM Inbox.

It mirrors the [Workfront Dashboard CSV Export](workfront-csv-export.md) 1:1 (config component +
composite multifield → Sling Model + JCR reader → generator service → cron scheduler → service
user + repoinit + Inbox notifier), so the two features are operationally identical.

---

## 1. How it works

```
UnpublishedPagesReport component      (author drops it on a config page under /content)
        │  authors set scan roots, excludes, threshold, output, columns
        ▼
UnpublishedPagesReportScheduler       (monthly; finds ALL config components via QueryBuilder)
        │  for each configuration
        ▼
UnpublishedPagesReportService         (batched traversal + per-page filtering, CSV → DAM, replicate)
        │
        ├─ success → /content/dam/mysite/reports/<fileName>-<yyyyMMdd>.csv  (activated)
        └─ failure → AEM Inbox notification to "administrators" + error log

UnpublishedReportRunServlet           (optional on-demand "Generate now" from the component)
```

Key design points:

- The scheduler reads configuration **directly from the JCR** (there is no HTTP request in a
  scheduler), so it works headless. The Sling Model renders only the author summary, and the
  Run-now servlet reads the same config with the requesting author's resolver.
- Page traversal is **batched** (`p.offset`/`p.limit`, 500 pages per batch) so a large scan root
  is fetched in bounded queries rather than one huge result set.
- Each **configuration is isolated** — one config failing never stops the others, and any failure
  raises an Inbox notification for the `administrators` group.
- CSV activation failure is **non-fatal**: the file is saved on author regardless.

---

## 2. Prerequisites (handled by deployment)

Created automatically on deploy via repoinit — no manual setup needed:

| Item | Value |
| --- | --- |
| System user | `unpublished-report-service` (under `/home/users/system/mysite`) |
| Service mapping | `com.mysite.mysite.core:unpublished-report-write=unpublished-report-service` |
| DAM output folder | `/content/dam/mysite/reports` |
| ACLs | read on `/content`; read+write+versionManagement+replicate on the DAM folder; read+write on `/var/taskmanagement` |

> The configured **CSV Output Folder** must live under the ACL root granted above
> (`/content/dam/mysite/reports`). If you point it elsewhere, extend the repoinit ACLs to match
> (see section 9).

---

## 3. The unpublished rule

A page is **included** in the report when **any** of the following is true:

1. It has **never been activated** — no `jcr:content/cq:lastReplicated`, **or**
2. Its last replication action was **not** an Activate (`cq:lastReplicationAction != "Activate"`,
   e.g. it was deactivated), **or**
3. Its last activation is **older than the threshold** (`cq:lastReplicated` earlier than
   `now − thresholdDays`).

A page is then **excluded** (never reported) when:

- Its path equals or sits under any configured **Exclude Path** prefix (e.g. `/content/test`), **or**
- It matches any configured **Exclude-property condition** — the named property equals the given
  value, checked on `jcr:content` first, then the page node
  (e.g. `excludeFromDelete = true`).

`cq:lastReplicated` is read as a `Calendar` from the ValueMap and compared numerically, so both
string- and date-typed values are handled correctly.

---

## 4. Configure the report (author)

1. Open (or create) any page under `/content` to hold the configuration — e.g. an internal
   "Reports Config" page.
2. In the page editor, add the **Unpublished Pages Report** component (component group
   **My Site - Structure**).
3. Open the dialog and fill the tabs:

   **Scope**

   | Field | Meaning | Default |
   | --- | --- | --- |
   | **Scan Roots** (multifield) | Content roots scanned for `cq:Page` nodes | `/content` |
   | **Exclude Paths** (multifield) | Path prefixes whose pages are skipped | `/content/test` |
   | **Exclude-property Conditions** (composite multifield) | Rows of *Property Name* + *Property Value*; a page matching any row is skipped | `excludeFromDelete` / `true` |

   **Rule**

   | Field | Meaning | Default |
   | --- | --- | --- |
   | **Not-published Threshold (days)** | Report pages never published, or last activated more than this many days ago | `90` |

   **Output**

   | Field | Meaning | Default |
   | --- | --- | --- |
   | **CSV Output Folder** | DAM folder the CSV is written into | `/content/dam/mysite/reports` |
   | **CSV Base File Name** | File name without extension; a `-yyyyMMdd` date suffix is appended | `unpublished-pages` |
   | **Activate CSV after generation** | Replicate the CSV after writing | checked (`true`) |

   **Columns** (composite multifield) — rows of *Header* + *Source*. `Source` is either a
   `jcr:content` property name (e.g. `cq:lastReplicated`) or a special token:

   | Token | Resolves to |
   | --- | --- |
   | `:title` | `jcr:content/jcr:title`, falling back to the page node name |
   | `:path` | The page path |
   | `:url` | `path + ".html"` (or an absolute publish URL when an `Externalizer` publish domain is configured) |
   | `:hash` | A stable, unique SHA-256 hex hash of the page URL (the `:url` value) — a per-page identifier that can be used to refer back to the exact page |

4. Save. The component shows a summary of the resolved configuration (and the default columns)
   in edit mode, plus a **Generate now** button.

**Every field applies a default when left empty**, so a freshly dropped component (without
opening the dialog) already produces a sensible report. You may drop the component on multiple
pages and/or configure multiple scan roots — the scheduler discovers every instance under
`searchRoot`.

> The default file for the defaults above is
> `/content/dam/mysite/reports/unpublished-pages-<yyyyMMdd>.csv`.

---

## 5. Scheduling

**PID / file:** `com.mysite.core.schedulers.UnpublishedPagesReportScheduler`
(`ui.config/.../config/com.mysite.core.schedulers.UnpublishedPagesReportScheduler.cfg.json`)

| Property | Description | Default |
| --- | --- | --- |
| `scheduler.expression` | Quartz cron expression | `0 0 2 1 * ?` (02:00 on the 1st of every month) |
| `scheduler.concurrent` | Allow concurrent runs | `false` |
| `searchRoot` | Path scanned for config components | `/content` |
| `pauseBetweenConfigsSeconds` | CPU cool-down (seconds) applied after each configuration, to let CPU settle before the next. `0` disables it; no pause after the last config. | `5` |

**Cron examples**

| Cadence | Expression |
| --- | --- |
| Every 5 minutes (testing) | `0 */5 * * * ?` |
| Daily 02:00 | `0 0 2 * * ?` |
| 1st of month 02:00 | `0 0 2 1 * ?` |
| Every hour | `0 0 * * * ?` |

---

## 6. Generator settings

**PID:** `com.mysite.core.services.impl.UnpublishedPagesReportServiceImpl`

| Property | Description | Default |
| --- | --- | --- |
| `pageBatchSize` | Pages fetched per QueryBuilder batch — bounds how many hits are held in memory at once. | `500` |

The output folder, file name, columns, and activation flag are **not** OSGi settings — they are
authored per component (section 4).

---

## 7. On-demand run

Two ways to trigger a run without waiting for the monthly cron:

- **Generate now** button — in the component's edit view; POSTs the component path to the servlet
  and reports the result (CSV path + row count) in an alert.
- **Servlet directly** — `POST /bin/mysite/unpublishedreport` with form parameter
  `configPath=<path to the component resource>`. Returns a JSON summary:

  ```bash
  curl -u admin:admin -X POST http://localhost:4502/bin/mysite/unpublishedreport \
       -d 'configPath=/content/mysite/reports-config/jcr:content/root/container/unpublishedpagesreport'
  # → {"success":true,"csvPath":"/content/dam/mysite/reports/unpublished-pages-20260731.csv","rowsReported":42}
  ```

  The servlet reads the config with the **caller's** resolver (so authoring permissions apply),
  then hands generation to the shared service, which writes the DAM asset as the service user.

Alternatively, temporarily set the scheduler's `scheduler.expression` to e.g. `0/20 * * * * ?`
in `/system/console/configMgr`, wait for it to fire, then restore the monthly cron.

---

## 8. Output format

CSV is RFC 4180 compliant (fields containing `,`, `"`, or newlines are quoted). The header row
and every data row follow the **configured columns**, in order. The default column set leads with
a **Hash** column — a unique, stable SHA-256 of the page URL that can be used to refer back to the
exact page:

```
Hash,Title,Page URL,Last Published,Replication Action,Last Modified,Modified By,Template,Resource Type
0b1c...e9,About Us,/content/mysite/us/en/about.html,,,2026-05-01T10:15:00+02:00,jdoe,/conf/mysite/settings/wcm/templates/page-content,mysite/components/page
```

- Date-typed properties (e.g. `cq:lastReplicated`, `cq:lastModified`) are formatted ISO-8601.
- Multi-value properties are joined with `;`.
- Missing values render as empty cells (note the empty *Last Published* / *Replication Action*
  above for a never-published page).

The scan includes the **root page** of each scan root **and** all descendant pages
(QueryBuilder `path.self=true`).

---

## 9. Scalability & performance

Pages are fetched in **bounded batches** (`p.limit=pageBatchSize` + `p.offset`) and filtered in
Java per hit, so memory is driven by the CSV being assembled, not by the size of `/content`.

**Tuning**

- `pageBatchSize` (generator config) trades query count against per-batch memory. `500` is a good
  default.
- `pauseBetweenConfigsSeconds` (scheduler config) inserts a CPU cool-down between configurations.
- `/content` can be very large — prefer a **narrower scan root** (e.g. a site branch) over
  `/content` where possible to keep scans fast.

**Known limits** — as with the Workfront exporter, offset paging is O(n²) for very deep trees and
the CSV is assembled in memory. Beyond roughly **50k–100k pages in one scan root**, switch to a
streaming JCR-SQL2 `ISDESCENDANTNODE` query and a file-backed CSV write (not implemented today).

---

## 10. Operations

**Failure notifications** — when a configuration fails, a Granite **Inbox notification** (task) is
raised for the `administrators` group by the scheduler, and the error is logged. Check the AEM
Inbox (`/aem/inbox`) or the logs. The Run-now servlet instead returns the error in its JSON
response.

**Isolation** — each configuration is processed independently; one failing configuration never
prevents the others from generating.

**Logs** — filter on `com.mysite.core.schedulers` and `com.mysite.core.services` in `error.log`.
Each run logs start/finish and per-configuration row counts.

---

## 11. Source / file map

| Layer | Path |
| --- | --- |
| Config Sling Model | `core/.../models/UnpublishedReportConfigModel(.java/Impl)` |
| DTOs, constants + JCR reader | `core/.../models/report/*` |
| Generator service | `core/.../services/UnpublishedPagesReportService(.java/Impl)` |
| Scheduler (reuses `SchedulerSupport`, `WorkfrontInboxNotifier`) | `core/.../schedulers/UnpublishedPagesReportScheduler.java` |
| Run-now servlet | `core/.../servlets/UnpublishedReportRunServlet.java` |
| Component (dialog, HTL, editConfig) | `ui.apps/.../components/unpublishedpagesreport/` |
| OSGi configs (scheduler, service user, repoinit) | `ui.config/.../osgiconfig/config/*unpublishedreport*`, `*UnpublishedPagesReportScheduler*` |

Component resource type: **`mysite/components/unpublishedpagesreport`**

---

## 12. Deploy

```bash
# Author (default localhost:4502)
mvn clean install -PautoInstallSinglePackage

# Publish
mvn clean install -PautoInstallSinglePackagePublish
```

After deploy, confirm registration in `/system/console/components`
(`UnpublishedPagesReportServiceImpl`, `UnpublishedPagesReportScheduler`,
`UnpublishedReportRunServlet`) and the system user under
`/useradmin` → `system/mysite/unpublished-report-service`.

---

## 13. Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| DS component **unsatisfied** / "Cannot derive user name for bundle" | Service-user mapping BSN mismatch. The mapping must use the real bundle symbolic name `com.mysite.mysite.core` and **no** `[brackets]` (brackets are AEMaaCS-only). |
| No CSV generated | Confirm a config component exists under `searchRoot`; confirm the scheduler cron fired (or use Run-now); check `error.log`. |
| CSV written but not activated | Replication queue/agent issue — non-fatal; the asset is saved on author. Re-run once the agent is healthy, or uncheck **Activate CSV**. |
| A page you expected is missing | It is under an **Exclude Path**, matches an **Exclude-property condition**, or was activated within the threshold window. |
| Unchecking **Activate CSV** had no effect | The checkbox stores `false` via the `@DefaultValue`/`@UseDefaultWhenMissing` hidden fields — re-save the dialog after upgrading if the component predates them. |
| `Scan root ... does not exist` in log | The configured **Scan Root** is wrong or not readable by the service user. |
| Component fails to render: `<Model> cannot be resolved to a type` | The model interface package `com.mysite.core.models` must be **exported** so the HTL Java compiler can resolve the `data-sly-use` FQN (already configured in the core `bnd`). |
| `:url` column shows only `path.html`, not an absolute URL | No `Externalizer` **publish** domain is configured. Configure the Externalizer or accept the relative form. |
