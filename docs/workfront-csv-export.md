# Workfront Dashboard CSV Export

Periodically exports AEM page metadata (**Title, Path, Brand, Last Modified, Modified By, Published, Next Review Date, Days For Next Review, Franchise, Page Owners, Template**) as CSV
files into the DAM, for consumption by Workfront dashboards. Built for AEM 6.5 (AMS),
self-healing, and observable via the AEM Inbox. Designed and validated to handle a content
tree of **up to ~10,000 pages** per CSV (see [Scalability & performance](#7-scalability--performance)).

---

## 1. How it works

```
WorkfrontDashboardConfigComponent   (author drops it on a config page)
        │  authors add one or more { Content Tree Path, CSV File Name } rows
        ▼
WorkfrontCsvScheduler               (weekly; finds ALL config components via QueryBuilder)
        │  for each configured content tree
        ▼
WorkfrontCsvGeneratorService        (batched traversal, CSV → DAM via AssetManager, replicate)
        │
        ├─ success → /content/dam/mysite/workfront-dashboard/<csvName>.csv  (activated)
        └─ failure → AEM Inbox notification to "administrators" + error log
        ▼
WorkfrontCsvVerificationScheduler   (weekly; re-generates any CSV missing from the DAM)
```

Key design points:

- The scheduler reads configuration **directly from the JCR** (there is no HTTP request in a
  scheduler), so it works headless. The Sling Model is used only to render the author summary.
- Page traversal is **batched** (`p.offset`/`p.limit`, 500 pages per batch) so a 10,000-page
  tree is fetched in ~20 bounded queries rather than one huge result set.
- Each content tree is **isolated** — one tree failing never stops the others.
- Replication failure is **non-fatal**: the CSV is saved on author and the verification
  scheduler re-publishes it on its next run.

---

## 2. Prerequisites (handled by deployment)

These are created automatically on deploy via repoinit — no manual setup needed:

| Item | Value |
| --- | --- |
| System user | `workfront-csv-service` (under `/home/users/system/mysite`) |
| Service mapping | `com.mysite.mysite.core:workfront-csv-write=workfront-csv-service` |
| DAM output folder | `/content/dam/mysite/workfront-dashboard` |
| ACLs | read on `/content`; read+write+replicate on the DAM folder; read+write on `/var/taskmanagement` |

---

## 3. Configure the export (author)

The component decides **which content trees** get exported and **what each CSV is named**.

1. Open (or create) any page where you want to hold the configuration — for example an
   internal "Dashboards Config" page. The page can be anywhere under `/content`.
2. In the page editor, add the **Workfront Dashboard Config** component
   (component group **My Site - Structure**).
3. Open the component dialog and, under the **Configuration** tab, add one or more rows to
   the **Content Trees** multifield:

   | Dialog field | Meaning | Example |
   | --- | --- | --- |
   | **Content Tree Path** | Root path whose pages are exported (all `cq:Page` descendants) | `/content/mysite/us/en` |
   | **CSV File Name** | Output file name, **without** extension | `us-en-pages` |

4. Save. The component shows a summary table of the configured trees in edit mode.

You may drop the component on **multiple** pages and/or add **multiple** rows — the scheduler
discovers every instance across `/content` and exports them all.

> The generated file for the example above will be
> `/content/dam/mysite/workfront-dashboard/us-en-pages.csv`.

---

## 4. Scheduling

Two schedulers run on independent cron expressions. Configure them in
`/system/console/configMgr` (or via the OSGi config files in `ui.config`).

| Purpose | OSGi PID / config file | Default cron |
| --- | --- | --- |
| Generate CSVs | `com.mysite.core.schedulers.WorkfrontCsvScheduler` | `0 0 10 ? * SAT` (Sat 10:00) |
| Verify & self-heal | `com.mysite.core.schedulers.WorkfrontCsvVerificationScheduler` | `0 0 8 ? * SUN` (Sun 08:00) |

Config file locations:

```
ui.config/src/main/content/jcr_root/apps/mysite/osgiconfig/config/
  com.mysite.core.schedulers.WorkfrontCsvScheduler.cfg.json
  com.mysite.core.schedulers.WorkfrontCsvVerificationScheduler.cfg.json
```

Editable properties (both schedulers):

| Property | Description | Default |
| --- | --- | --- |
| `scheduler.expression` | Quartz cron expression | see table above |
| `scheduler.concurrent` | Allow concurrent runs | `false` |
| `searchRoot` | Path scanned for config components | `/content` |
| `pauseBetweenTreesSeconds` | Cool-down (seconds) applied after each tree's CSV is written, to let CPU settle before the next tree. `0` disables it. No pause is applied after the last tree. | `5` |

**Verification scheduler — additional properties** (`WorkfrontCsvVerificationScheduler`):

| Property | Description | Default |
| --- | --- | --- |
| `freshnessThresholdMinutes` | A CSV last modified longer ago than this (in **minutes**) is considered stale and re-generated. Set to `0` to disable the staleness check. | `60` |
| `minCsvSizeBytes` | A CSV smaller than this many bytes is considered empty and re-generated. | `1` |

The verification scheduler treats a CSV as **healthy** (and leaves it untouched) only when it
is **present**, **at least `minCsvSizeBytes` bytes**, and was **updated within the last
`freshnessThresholdMinutes` minutes**. Anything missing, empty, or stale is re-generated.

**Cron examples**

| Cadence | Expression |
| --- | --- |
| Every 5 minutes (testing) | `0 */5 * * * ?` |
| Every hour | `0 0 * * * ?` |
| Daily 02:00 | `0 0 2 * * ?` |
| Saturdays 10:00 | `0 0 10 ? * SAT` |

---

## 5. Generator settings

**PID:** `com.mysite.core.services.impl.WorkfrontCsvGeneratorServiceImpl`
**File:** `ui.config/.../config/com.mysite.core.services.impl.WorkfrontCsvGeneratorServiceImpl.cfg.json`

| Property | Description | Default |
| --- | --- | --- |
| `damRootPath` | DAM folder CSV files are written into | `/content/dam/mysite/workfront-dashboard` |
| `pageBatchSize` | Pages fetched per QueryBuilder batch — bounds how many pages are held in memory at once. For a 10,000-page tree this yields ~20 batches. | `500` |

> If you change `damRootPath`, update the repoinit ACLs and the `ui.content` vault filter to
> match (see section 9).

---

## 6. Output format

CSV is RFC 4180 compliant (fields containing `,`, `"`, or newlines are quoted). One row per
`cq:Page` under each configured tree:

```
Hash,Title,Path,Brand,Last Modified,Modified By,Published,Next Review Date,Days For Next Review,Franchise,Page Owners,Template
0b1c...e9,Homepage,/content/mysite/us/en,mysite,2026-07-30T10:15:00.000+02:00,jdoe,True,2026-12-31,114,retail,borrow,/conf/mysite/settings/wcm/templates/page-content
```

Columns are read from the page's `jcr:content` unless noted:

| Column | Source |
|---|---|
| **Hash** | Unique, stable SHA-256 of the page URL `path + ".html"` — a per-page identifier |
| **Title** | `jcr:title` (falls back to node name) |
| **Path** | Page path |
| **Brand** | Segment after `/content` in the path, e.g. `/content/mysite/us/en` → `mysite` |
| **Last Modified** | `cq:lastModified` |
| **Modified By** | `cq:lastModifiedBy` (falls back to `jcr:lastModifiedBy`) |
| **Published** | `True`/`False` from the page's replication status (`ReplicationStatus.isActivated()`) |
| **Next Review Date** | `contentReviewExpiryDate` (Content Governance tab) |
| **Days For Next Review** | Whole days from the scheduler run date until **Next Review Date** — negative when overdue (e.g. `-10`); blank if no/invalid date |
| **Franchise** | `franchise` (Content Governance tab) |
| **Page Owners** | `pageOwners` (Content Governance tab) |
| **Template** | `cq:template` |

> Franchise and Page Owners are the stored **keys** (e.g. `retail`, `borrow`), not the display labels.

The export includes the **root page** of each configured tree **and** all descendant pages
(the QueryBuilder `path.self=true` option), so `/content/mysite/us` exports the `us` page plus
everything beneath it.

---

## 7. Scalability & performance

**Target: up to ~10,000 pages in a single content tree.** The design handles this comfortably.

**How it scales**

| Aspect | Behaviour | At 10,000 pages |
| --- | --- | --- |
| Page fetching | Batched QueryBuilder (`p.limit=pageBatchSize` + `p.offset`), looping until a short batch | ~20 queries; never loads all 10k hits at once |
| Memory | The CSV is assembled in memory, then written once to the DAM | One row ≈ 150 bytes → ~1.5 MB of text + ~1.5 MB byte buffer ≈ **~4 MB transient per tree** |
| DAM writes | One `AssetManager.createAsset(...)` + one `commit()` per tree | 1 write, 1 replication per CSV |
| Isolation | Each tree runs independently; a failure raises an Inbox notification and does not stop other trees | Unaffected |

Because trees are processed one at a time, total memory is driven by the **largest single
tree**, not the sum of all trees.

**Tuning**

- `pageBatchSize` (generator config) trades query count against per-batch memory. `500` is a
  good default for 10k-page trees. Lower it on memory-constrained instances; raising it rarely
  helps.
- `pauseBetweenTreesSeconds` (scheduler config) inserts a CPU cool-down between trees so a run
  over many/large trees does not sustain a CPU spike. Default `5`s; increase it if runs still
  stress the instance, or set `0` to process back-to-back. The pause is skipped after the last
  tree. With N trees the run takes roughly `sum(traversal time) + (N-1) × pause`.
- Prefer configuring **several smaller trees** over one enormous tree where possible — it
  keeps each file easy to consume and lets the cool-down spread the load.

**Known limits / when to revisit the design**

The current approach is validated for ~10k pages. Beyond roughly **50k–100k pages in one
tree**, consider these enhancements (not implemented today):

1. **Streaming query** instead of deep `p.offset` paging — offset paging is O(n²) because each
   batch re-scans from the start; a streaming JCR-SQL2 `ISDESCENDANTNODE` query removes that cost.
2. **Stream the CSV to a temp file** instead of building it in memory — keeps memory flat
   regardless of tree size.
3. **Write as a plain `nt:file`** (or exclude the DAM folder from the *DAM Update Asset*
   workflow) to skip per-CSV rendition/thumbnail generation — this is also what produces the
   benign `FolderPreviewUpdaterImpl` log noise (see Troubleshooting).

---

## 8. Operations

**Failure notifications** — when a tree fails to export, a Granite **Inbox notification**
(task) is raised for the `administrators` group, and the error is logged. Check the AEM
Inbox (`/aem/inbox`) or the logs.

**Self-healing** — the verification scheduler checks every configured CSV and re-generates any
that are **missing**, **empty** (below `minCsvSizeBytes`), or **stale** (last modified more than
`freshnessThresholdMinutes` minutes ago). This catches trees that failed, assets that never
replicated, zero-row exports, and files that silently stopped updating.

**Manual / one-off run** — to trigger a generation without waiting for the weekly cron,
temporarily set `scheduler.expression` on `WorkfrontCsvScheduler` to e.g. `0/20 * * * * ?`
in `/system/console/configMgr`, wait for it to fire, then restore the production cron.

**Logs** — filter on `com.mysite.core.schedulers` and `com.mysite.core.services` in
`error.log`. Each run logs start/finish and per-tree page counts.

---

## 9. Source / file map

| Layer | Path |
| --- | --- |
| Config Sling Model | `core/.../models/WorkfrontDashboardConfigModel(.java/Impl)` |
| Content-tree DTO + JCR reader | `core/.../models/workfront/*` |
| Generator service | `core/.../services/WorkfrontCsvGeneratorService(.java/Impl)` |
| Schedulers + Inbox notifier | `core/.../schedulers/Workfront*` |
| Component (dialog, HTL, editConfig) | `ui.apps/.../components/workfront-dashboard-config/` |
| OSGi configs (schedulers, generator, service user, repoinit) | `ui.config/.../osgiconfig/config/` |
| DAM output filter | `ui.content/.../META-INF/vault/filter.xml` |

Component resource type: **`mysite/components/workfront-dashboard-config`**

---

## 10. Deploy

```bash
# Author (default localhost:4502)
mvn clean install -PautoInstallSinglePackage

# Publish
mvn clean install -PautoInstallSinglePackagePublish
```

---

## 11. Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| Bundle `com.mysite.mysite.core` stuck in **Installed** | Package import version mismatch (e.g. `commons-lang3`). Check the bundle's imports in `/system/console/bundles`; relax the range in the core `bnd` config. |
| DS component **unsatisfied** / "Cannot derive user name for bundle" | Service-user mapping BSN mismatch. The mapping must use the real bundle symbolic name `com.mysite.mysite.core` and **no** `[brackets]` (brackets are AEMaaCS-only). |
| No CSV generated | Confirm a config component with a valid row exists under `searchRoot`; confirm the scheduler cron fired; check `error.log`. |
| CSV on author but not on publish | Replication queue issue — non-fatal; the verification scheduler will re-publish. Check the replication agent. |
| `Content tree path does not exist` in log | The configured **Content Tree Path** is wrong or not readable by the service user. |
| Component fails to render: `<Model> cannot be resolved to a type` (Sightly compilation error) | The model **interface** package must be **exported** so the HTL Java compiler can resolve the `data-sly-use` FQN. The core `bnd` config exports `com.mysite.core.models` for this reason. |
| `FolderPreviewUpdaterImpl error while executing folder thumbnail update job` in the log after a run | Benign AEM-internal noise, **not** from this utility — AEM tries to build a DAM folder thumbnail from the CSV (a non-image) and fails. Safe to ignore. |
