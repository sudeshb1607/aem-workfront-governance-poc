# Content Governance Tab — Page Properties

## Overview

The Content Governance tab appears in the AEM Page Properties dialog and lets authors classify every page with governance metadata: franchise ownership, page owner, FPA approval status, and a content review expiry date.

The dropdown options for **Franchise** and **Page Owner** are not hardcoded — they are authored by a site admin using the `Content Governance Config` component, placed once per locale site. This means options can be updated without a code deployment.

**Page Owners are scoped per Franchise.** Each Franchise owns its own list of Page Owners, authored as a **nested multifield** inside each Franchise row on the config component. In Page Properties, selecting a Franchise re-filters the Page Owner dropdown to that Franchise's owners only — a client-side cascade driven by a dedicated clientlib and JSON endpoint.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Page Properties Dialog                       │
│          _cq_dialog/.../contentGovernance/.content.xml              │
│                                                                     │
│  ┌──────────────┐  ┌────────────────┐  ┌───────────┐  ┌─────────┐  │
│  │  Franchise   │  │  Page Owners   │  │ Tag Type  │  │  Date   │  │
│  │   <select>   │  │   <select>     │  │ (static)  │  │ picker  │  │
│  │ .cg-franchise│  │ .cg-page-owners│  └───────────┘  └─────────┘  │
│  │ datasource   │  │  (JS-driven)   │                              │
│  └──────┬───────┘  └───────▲────────┘                              │
└─────────┼──────────────────┼─────────────────────────────────────── ┘
          │                  │
          │                  │  clientlib-content-governance (cq.authoring.dialog)
          │                  │  on load + on Franchise change:
          │                  └── GET /bin/mysite/content-governance/page-owners.json
          │                          ?item=<path>&franchise=<key>
          ▼                                  │
  sling:resourceType                         ▼
  content-governance-           ContentGovernancePageOwnersServlet
  franchise                                  │
          │                                  ├── deriveSiteRoot(item)
          ▼                                  ├── findConfigComponent(...)
   ContentGovernanceOptionsDataSourceServlet │
          │                                  └── franchiseOptions → row[key==franchise]
          ├── reads ?item param              │       → nested pageOwners → JSON array
          ├── derives site root              │
          ├── JCR SQL-2 → content-governance-config
          └── reads franchiseOptions → select options
                   │                         │
   (deriveSiteRoot + findConfigComponent are static helpers, shared by both servlets)
                   ▲                         ▲
                   │ Options authored here   │
   ┌──────────────────────────────────────────────┐
   │    content-governance-config component        │
   │    (placed by admin)                          │
   │                                               │
   │  franchiseOptions (composite multifield)      │
   │    item0: key="retail", label="Retail"        │
   │            pageOwners (nested multifield)      │
   │              item0: key="borrow", label="..."  │
   │              item1: key="save",   label="..."  │
   │    item1: key="corporate", label="Corporate"  │
   │            pageOwners                          │
   │              item0: key="treasury", ...        │
   └──────────────────────────────────────────────┘
```

---

## Files

### Dialog — Page Properties tab

| File | Purpose |
|---|---|
| `ui.apps/.../page/_cq_dialog/content/items/tabs/items/contentGovernance/.content.xml` | Defines the Content Governance tab with its four fields |

### DataSource — Servlet + resource type stub

| File | Purpose |
|---|---|
| `core/.../servlets/ContentGovernanceOptionsDataSourceServlet.java` | Datasource servlet for the **Franchise** select; queries the config component and returns `franchiseOptions` as select options. Exposes `deriveSiteRoot` + `findConfigComponent` as `static` helpers |
| `ui.apps/.../datasource/content-governance-franchise/.content.xml` | Empty `sling:Folder` — the resource type target for the Franchise datasource |

### Cascade endpoint — Page Owners for a Franchise

| File | Purpose |
|---|---|
| `core/.../servlets/ContentGovernancePageOwnersServlet.java` | Path-bound JSON servlet at `/bin/mysite/content-governance/page-owners`; reads `item` + `franchise` params, navigates to the matching Franchise row's nested `pageOwners` and returns `[{value,text}]` |

### Clientlib — cascading behavior

| File | Purpose |
|---|---|
| `ui.apps/.../clientlibs/clientlib-content-governance/.content.xml` | `cq:ClientLibraryFolder`, `categories=[cq.authoring.dialog]`, `allowProxy=true` |
| `ui.apps/.../clientlibs/clientlib-content-governance/js/content-governance.js` | On dialog load and on Franchise change, GETs the cascade endpoint and rebuilds the `.cg-page-owners` select; re-selects the saved owner if it still exists |

### Config component — where options are authored

| File | Purpose |
|---|---|
| `ui.apps/.../components/content-governance-config/.content.xml` | Component definition (`componentGroup: My Site - Structure`) |
| `ui.apps/.../components/content-governance-config/_cq_dialog/.content.xml` | Component dialog with the `franchiseOptions` composite multifield; each row contains a **nested** `pageOwners` composite multifield |
| `ui.apps/.../components/content-governance-config/content-governance-config.html` | Render script — outputs a visible placeholder in Edit/Preview mode only; no production HTML |

---

## Fields in the Tab

| Field | Type | Stored property | Options source |
|---|---|---|---|
| Franchise | Select (required) | `./franchise` | `franchiseOptions` on the `content-governance-config` component (via datasource) |
| Page Owners | Select (required) | `./pageOwners` | Nested `pageOwners` under the selected Franchise (via cascade endpoint + clientlib) |
| Tag Type | Select (required) | `./tagType` | Hardcoded in dialog XML |
| Content Review Expiry Date | Datepicker (required) | `./contentReviewExpiryDate` | N/A — `YYYY-MM-DD` format |

### Tag Type values (hardcoded)

| Display text | Stored value |
|---|---|
| FPA approval – not required | `fpa-not-required` |
| FPA approval – required | `fpa-required` |
| FPA approval – pending | `fpa-pending` |
| FPA approval – approved | `fpa-approved` |

---

## How the Franchise DataSource Servlet Works

1. Granite UI renders the Franchise `<select>` field and fires a GET request to the datasource resource type
2. Sling resolves `mysite/datasource/content-governance-franchise` and dispatches to `ContentGovernanceOptionsDataSourceServlet`
3. It reads the current page path from the `item` request parameter (automatically set by the page properties dialog), falling back to the request suffix
4. It derives the **site root** as the first three path segments — e.g. `/content/mysite/us/en/some/page` → `/content/mysite/us`
5. It runs a JCR SQL-2 query to find the first node with `sling:resourceType = mysite/components/content-governance-config` anywhere under that site root
6. It iterates the `franchiseOptions` multifield child nodes, reading the `key` and `label` properties from each row
7. It returns a `SimpleDataSource` of `ValueMapResource` entries (`value=key`, `text=label`), which Granite UI renders as `<option>` elements

---

## How the Page Owner Cascade Works

The Page Owner `<select>` has **no datasource** — it is populated entirely by the clientlib:

1. `clientlib-content-governance` (category `cq.authoring.dialog`) loads with the page properties dialog
2. On `foundation-contentloaded`, if `.cg-franchise` and `.cg-page-owners` both exist, the JS reads the page path (from the `item` query param) and the currently-selected Franchise
3. It GETs `/bin/mysite/content-governance/page-owners.json?item=<path>&franchise=<key>`
4. `ContentGovernancePageOwnersServlet` reuses `deriveSiteRoot` + `findConfigComponent` (static helpers on the Franchise servlet), locates the `franchiseOptions` row whose `key` matches `franchise`, and returns its nested `pageOwners` as `[{"value":key,"text":label}, ...]`
5. The JS rebuilds the `.cg-page-owners` Coral Select items and re-selects the saved owner **if it still exists** in the new list
6. On Franchise `change`, it refetches, repopulates, clears the stale selection, and re-triggers `change` + `foundation-field-change` so Granite validation stays in sync

---

## Setting Up the Config Component

1. In AEM Sites, navigate to a page under your locale root (e.g. `/content/mysite/us/en`)
2. Create or open a page designated for governance configuration (e.g. `/content/mysite/us/en/governance-config`)
3. Add the **Content Governance Config** component (group: `My Site - Structure`) to the page
4. Open the component dialog and add **Franchise** rows. Inside each Franchise row, add its own **Page Owners** in the nested multifield:

   **Franchise Options**
   - Key: `retail` / Label: `Retail`
     - Page Owners → Key: `borrow` / Label: `Borrow Money`
     - Page Owners → Key: `save` / Label: `Savings`
   - Key: `corporate` / Label: `Corporate`
     - Page Owners → Key: `treasury` / Label: `Treasury`

5. Save. In Page Properties on any page under `/content/mysite/us`, the Franchise dropdown shows the configured franchises, and selecting one filters Page Owners to just that franchise's owners.

> **Note:** The `key` is the value stored in page properties. The `label` is only for display. Changing a key after pages have been authored will leave existing pages with the old stored value.

> **Deleting a Franchise row** removes its nested Page Owners with it — the exact add/remove semantics, fully native to the composite multifield.

---

## Multi-Locale Support

The servlet scopes its config lookup to the first three path segments of the edited page:

```
/content/mysite/us/en/... → searches under /content/mysite/us
/content/mysite/uk/en/... → searches under /content/mysite/uk
```

This means each locale can have its own `content-governance-config` component with a different set of franchise and page owner options.

---

## Extending the Tab

### Adding a new static field
Add a new Granite UI field node under the `<column>` in the tab's `.content.xml`. Use `name="./yourProperty"` so the value is saved to the page's `jcr:content` node.

### Adding a new dynamic dropdown
1. Create a new `sling:Folder` node under `apps/mysite/datasource/` as the resource type stub
2. Register a new (or extend the existing) servlet against that resource type
3. Add the new `<select>` field to the tab XML with a `<datasource>` child pointing to your new resource type
4. Add the corresponding multifield to the `content-governance-config` component dialog

### Adding another cascading (dependent) dropdown
Follow the Page Owner pattern: add a nested multifield under the parent's row in the config dialog, add a plain `<select>` (no datasource) with a marker `granite:class` in the tab, expose a path-bound JSON endpoint keyed by the parent value, and extend the clientlib to fetch + repopulate on the parent's `change`.

---

## Key Design Decisions

| Decision | Reason |
|---|---|
| Page Owners as a **nested multifield** under each Franchise row | Native Coral 3 support; adding a Franchise brings its owners, deleting one removes them — the exact add/remove semantics with zero tech debt. A per-Franchise dialog *tab* was rejected: Granite tabs are statically declared and generating them from data needs custom rendering + fragile listeners |
| Page Owners served by a **path-bound JSON endpoint + clientlib**, not a datasource | The list depends on the *current* Franchise selection, which a server-side datasource cannot see at render time; the cascade must re-run client-side on every Franchise change |
| `deriveSiteRoot` + `findConfigComponent` as **static helpers** | Both servlets locate the same config component the same way; sharing avoids duplication |
| Options authored in JCR via a component, not OSGi config | Site admins can update options in the AEM authoring UI without a code deployment |
| `key` / `label` separation | The stored `key` is stable and machine-readable; the `label` can be changed freely without affecting existing page data |
| Site root scoping (3 path segments) | Allows different option sets per locale while using a single shared config lookup |

---

## Backward Compatibility / Migration

The top-level `pageOwnerOptions` multifield has been **removed**. Any previously-authored
`pageOwnerOptions` data is orphaned and must be re-entered under the appropriate Franchise
rows' nested `pageOwners`. Pages already saved with a `./pageOwners` value keep that value;
on next dialog open the clientlib re-selects it **only if** that owner exists in the selected
Franchise's list — otherwise the author must reselect. This is a config-only feature (no known
production data), so re-authoring is acceptable.
