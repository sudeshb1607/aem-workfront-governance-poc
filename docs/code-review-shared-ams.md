# Code Review — AEM 6.5 project on a shared AMS instance

**Date:** 2026-07-31
**Reviewer:** Automated code review (Claude Code)
**Scope:** `core` bundle (Java), `ui.apps`, `ui.content`, `ui.config`
**Deployment target:** Adobe Managed Services (AMS) instance **shared by multiple projects/tenants**, deployed via **Cloud Manager** (SonarQube + OakPAL quality gates)

> **Status (updated 2026-07-31):** Some findings have been remediated — see the
> **Remediation status** section below. Items are also marked ✅ RESOLVED / ⚠️ ACCEPTED
> inline.

## Remediation status

| # | Finding | Status | Notes |
|---|---------|--------|-------|
| 4 | Export-Package leaks impl packages | ✅ **RESOLVED** | `Export-Package` removed from `core/pom.xml`; models still register via auto-generated `Sling-Model-Classes` (verified in packaged manifest). |
| 2 | `ContentAuditServlet` robustness/quality | ✅ **RESOLVED** | Safe parsing + clamping, validated & configurable `path`, no empty catch, explicit imports, `serialVersionUID`, `transient` reference. |
| 2 | Cross-tenant read (report scan scope + ACLs) | ⚠️ **ACCEPTED** | Left configurable per product decision; `DEFAULT_SCAN_ROOT` and service-user ACLs unchanged. Governance note retained. |
| 1 | Workflow datasource write-on-GET | ⚠️ **ACCEPTED / DEFERRED** | Left as-is: the shipped `/conf` model is design-time only and does not create the `/var` runtime model `getModels()` needs, so the mutation is load-bearing for the batch-workflow dropdown. |
| 3 | Path-bound `/bin` servlets | ⚠️ **ACCEPTED** | Endpoints kept as-is per product decision. |

Verification of resolved items: `mvn -pl core compile/package/test` → build success, **38 tests, 0 failures**; manifest confirms no internal `Export-Package` and `Sling-Model-Classes` intact.

## Overview

The recurring theme is **scope**: much of this code reaches beyond `/content/mysite` and
mutates *global/shared* structures (`/var/workflow/models`, `/var/workflow/packages`,
`/content` queries, OOTB DAM console overlays, broad ACLs). On a dedicated instance several
of these would be low severity; on a **shared AMS instance** they become high-impact because
they affect other tenants and the shared platform.

Severity legend: 🔴 Critical · 🟠 High · 🟡 Medium

---

## 🔴 Critical

### 1. GET datasource servlet mutates & deletes global workflow models — ⚠️ ACCEPTED / DEFERRED
**File:** `core/src/main/java/com/mysite/core/servlets/BatchWorkflowModelsDataSourceServlet.java:59-181`

A **read** servlet (`SlingSafeMethodsServlet.doGet`, populating a dropdown) calls
`ensureModelRegistered()` on every render, which `createNewModel()` / `deployModel()` and —
worse — `wfSession.deleteModel(...)` (`:111`) against `/var/workflow/models`, a **globally
shared** location.

- Violates HTTP/Sling semantics: `doGet` must be side-effect free. Any GET (monitor, crawler,
  pre-fetch) now writes to the repository.
- Destructive: deletes any model titled "MySite Batch Publish" and rebuilds it — race
  conditions with concurrent authors, and it churns a global structure other projects share.
- Hardcoded assignee `REVIEW_PARTICIPANT = "admin"` (`:51`) — assigning workflow items to the
  `admin` user is a governance red flag; should be a project group.

**Recommendation:** Ship the workflow model as content under
`/conf/global/settings/workflow/models/...` (already present in the `ui.content` filter),
deployed as a package. Never synthesize/delete models at request time.

> ⚠️ **ACCEPTED / DEFERRED (2026-07-31):** Left as-is. The shipped `/conf` model is a
> *design-time* model only (START→PROCESS→END, no review/JSON steps) and does not create the
> `/var/workflow/models` runtime model that `getModels()` lists, so `ensureModelRegistered()`
> is currently load-bearing for the batch-workflow dropdown. Removing it would require first
> making the runtime model deploy from content.

### 2. `ContentAuditServlet` queries and exposes *all tenants'* content — 🟡 PARTIALLY RESOLVED
**File:** `core/src/main/java/com/mysite/core/servlets/ContentAuditServlet.java`

> ✅ **RESOLVED (2026-07-31):** robustness/quality items — `parseInt` replaced with
> `NumberUtils.toInt` + clamping (no more 500s; `pageSize` capped), `path` is now a
> validated/configurable param constrained to `/content`, empty catch replaced with a logged
> debug, wildcard imports made explicit, `serialVersionUID` + `transient` reference added.
> ⚠️ **ACCEPTED:** the `/content`-wide scan (cross-tenant reach) is left configurable per
> product decision rather than hard-scoped to `/content/mysite`.

- `params.put("path", "/content")` (`:45`) returns **every project's pages**, leaking `owner`,
  `lastModifiedBy`, and paths across tenants. Must be scoped to `/content/mysite`.
- `Integer.parseInt(...)` on raw request params (`:37-38`) → unhandled `NumberFormatException`
  = HTTP 500 on non-numeric input; also no upper bound on `pageSize`.
- `catch (Exception ignored) {}` (`:88`) silently swallows all per-hit errors.
- Wildcard imports (`com.day.cq.search.*`, `java.util.*`, `org.apache.sling.api.resource.*`) —
  Sonar / coding-standard violation.
- No auth/role check. Defaulting `lastModifiedBy` to `"admin"` (`:65,71`) produces misleading data.

### 3. Path-bound servlets under `/bin` + no path validation — ⚠️ ACCEPTED
**Files:** `BatchWorkflowServlet.java:39`, `BatchWorkflowItemsServlet.java:25`,
`ContentAuditServlet.java:24`, `UnpublishedReportRunServlet.java:49`

- **Cloud Manager flags `sling.servlet.paths`** ("Sling Servlets should not be mounted using
  paths"). Prefer `sling.servlet.resourceTypes` + selector/extension. Path servlets also
  require explicit dispatcher allow-listing, which on a shared AMS dispatcher is easy to get
  wrong or blocked.
- `BatchWorkflowServlet` starts workflows over arbitrary `item` paths (`:57,138`) with **no
  allow-list** — a caller can target any JCR path, and it writes package nodes into the global
  `/var/workflow/packages` (`:47,127`) that are not reliably cleaned up on failure paths.

### 4. Export-Package leaks implementation packages — ✅ RESOLVED
**File:** `core/pom.xml` → (removed) `Export-Package: com.mysite.core, com.mysite.core.models`

> ✅ **RESOLVED (2026-07-31):** `Export-Package` removed from the bnd block. Verified in the
> packaged `MANIFEST.MF` that no internal package is exported and `Sling-Model-Classes` still
> lists the models, so Sling Model registration and HTL `data-sly-use` resolution are unaffected.

Exporting the whole `core` and `models` packages publishes internal classes as OSGi API to the
shared platform. Cloud Manager / OakPAL discourage this — it creates cross-bundle coupling and
versioning fragility. Export only a deliberate, versioned public API package (usually none for
a leaf project).

---

## 🟠 High

### 5. Overly broad service-user ACLs (cross-tenant read)
**Files:** `ui.config/.../RepositoryInitializer~unpublishedreport.cfg.json`,
`RepositoryInitializer~workfront.cfg.json`

Both grant `allow jcr:read on /content` — read access to **all tenants' content**. Scope to
`/content/mysite` (plus the DAM output folder). Both also create/write `/var/taskmanagement`
globally.

### 6. DAM console overlay affects every tenant
**File:** `ui.apps/.../META-INF/vault/filter.xml` →
`/apps/dam/gui/content/assets/jcr:content/actions/selection` (mode=update) and the
`createworkflow` action.

Overlaying the OOTB Assets action bar means the **"Batch Workflow" button appears for every
user in every DAM folder** across all projects on the shared author. This is shared-UI
contamination. Consider gating by path/group, or treat it as a platform-wide change requiring
sign-off.

### 7. `Thread.sleep` on the shared Sling scheduler thread pool
**Files:** `SchedulerSupport.java:41`, used by `UnpublishedPagesReportScheduler.java:109`

Blocking a pooled Quartz/Sling thread for `pauseBetweenConfigsSeconds × N` starves scheduled
jobs of *other* projects sharing that pool. Prefer smaller query batches / yielding rather than
sleeping a pooled thread.

### 8. Demo/archetype code shipping to production
**Files:** `LoggingFilter.java`, `SimpleResourceListener.java`, `SimpleServlet.java`,
`SimpleScheduledTask.java`, `HelloWorldModel.java`

- `LoggingFilter` — request-scope filter, runs on **every request for every project**; also
  unchecked `(SlingHttpServletRequest)` cast (`:53`) → `ClassCastException` risk.
- `SimpleResourceListener` — `immediate=true`, listens to the **entire** repo with no path
  filter (`:33-47`).
- Each adds per-request / per-change overhead on a shared instance. Remove leftover samples.

### 9. CSV formula injection
**File:** `UnpublishedPagesReportServiceImpl.escape()` (`:388`) and the Workfront CSV generator.

RFC-4180 quoting is handled, but not **formula injection** — values starting with `=`, `+`,
`-`, `@` execute when the governance CSV is opened in Excel. Prefix such cells with `'`.

---

## 🟡 Medium

- **In-memory CSV accumulation** — `UnpublishedPagesReportServiceImpl` builds the whole report
  in one `StringBuilder` then `getBytes()` (`:134,362`), doubling memory. Query batching is
  good, but output is not streamed — OOM risk on large trees / shared heap. Consider streaming.
- **Information disclosure** — servlets return `e.getMessage()` to the client
  (`BatchWorkflowServlet.java:111`, `UnpublishedReportRunServlet.java:102`). Log server-side;
  return a generic message.
- **CORS ships a dev origin** — `config.author/com.adobe.granite.cors.impl.CORSPolicyImpl~mysite.cfg.json`
  allows `http://localhost:3000`. Don't ship dev origins to a shared prod instance.
- **Broad `catch (Exception)`** across servlets/workflow steps (e.g.
  `BatchWorkflowServlet.java:108`, `BatchWorkflowModelsDataSourceServlet.java:83`); the empty
  catch in `ContentAuditServlet` is the worst offender.
- **Missing `serialVersionUID`** on `BatchWorkflowServlet`, `ContentAuditServlet`,
  `BatchWorkflowItemsServlet`, `BatchWorkflowModelsDataSourceServlet`.
- **Unclosed adapted sessions** — `BatchPublishWorkflowProcess` / `BatchAssetJsonProcess` adapt
  `WorkflowSession` to `ResourceResolver`/`Session`; confirm these are engine-managed
  (they are for workflow) so no leak.
- **`getOrCreatePath` creates `nt:unstructured`** for `/var/workflow/packages` segments
  (`BatchWorkflowServlet.java:157`) — guard the known root type.

---

## Cloud Manager quality-gate summary

Likely gate hits when this runs through Cloud Manager:

- **Path-bound servlets** (`sling.servlet.paths`)
- **Export-Package of impl packages**
- **Wildcard imports**
- **Empty / broad catch blocks**
- **Code coverage** — the new `servlets/`, `workflow/`, and `ContentAuditServlet` have no tests
  (only the report classes do), which can push custom-code coverage below the 50% gate.

---

## Top 5 to fix before deploying to shared AMS

1. Remove repository mutation from `BatchWorkflowModelsDataSourceServlet` (ship the model as content).
2. Scope `ContentAuditServlet` + service-user ACLs to `/content/mysite`, and fix the
   `parseInt` / empty-catch.
3. Move off `/bin` path servlets to resource-type binding; validate `item` paths.
4. Narrow `Export-Package`.
5. Delete the archetype demo classes (`LoggingFilter`, `SimpleResourceListener`, `Simple*`,
   `HelloWorldModel`).
