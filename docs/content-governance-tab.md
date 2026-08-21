# Content Governance Tab — Page Properties

## Overview

The Content Governance tab appears in the AEM Page Properties dialog and lets authors classify every page with governance metadata: franchise ownership, page owner, FPA approval status, and a content review expiry date.

The dropdown options for **Franchise** and **Page Owner** are not hardcoded — they are authored by a site admin using the `Content Governance Config` component, placed once per locale site. This means options can be updated without a code deployment.

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
│  │              │  │                │  └───────────┘  └─────────┘  │
│  │ datasource ──┼──┼── datasource   │                              │
│  └──────┬───────┘  └───────┬────────┘                              │
└─────────┼──────────────────┼─────────────────────────────────────── ┘
          │                  │
          ▼                  ▼
  sling:resourceType   sling:resourceType
  content-governance-  content-governance-
  franchise            page-owners
          │                  │
          └────────┬──────────┘
                   │ Sling resolves both to the same servlet
                   ▼
   ContentGovernanceOptionsDataSourceServlet
                   │
                   ├── reads ?item param → current page path
                   ├── derives site root (first 3 path segments)
                   ├── JCR SQL-2 query → finds content-governance-config
                   │   component anywhere under site root
                   └── reads franchiseOptions / pageOwnerOptions
                       multifield children → returns as select options

                   ▲
                   │ Options authored here
   ┌───────────────────────────────────┐
   │    content-governance-config      │
   │    component (placed by admin)    │
   │                                   │
   │  franchiseOptions (multifield)    │
   │    item0: key="retail"            │
   │            label="Retail"         │
   │    item1: key="corporate"         │
   │            label="Corporate"      │
   │                                   │
   │  pageOwnerOptions (multifield)    │
   │    item0: key="borrow-money"      │
   │            label="Borrow Money"   │
   └───────────────────────────────────┘
```

---

## Files

### Dialog — Page Properties tab

| File | Purpose |
|---|---|
| `ui.apps/.../page/_cq_dialog/content/items/tabs/items/contentGovernance/.content.xml` | Defines the Content Governance tab with its four fields |

### DataSource — Servlet + resource type stubs

| File | Purpose |
|---|---|
| `core/.../servlets/ContentGovernanceOptionsDataSourceServlet.java` | Servlet registered against both datasource resource types; queries the config component and returns select options |
| `ui.apps/.../datasource/content-governance-franchise/.content.xml` | Empty `sling:Folder` — the resource type target for the Franchise datasource |
| `ui.apps/.../datasource/content-governance-page-owners/.content.xml` | Empty `sling:Folder` — the resource type target for the Page Owner datasource |

### Config component — where options are authored

| File | Purpose |
|---|---|
| `ui.apps/.../components/content-governance-config/.content.xml` | Component definition (`componentGroup: My Site - Structure`) |
| `ui.apps/.../components/content-governance-config/_cq_dialog/.content.xml` | Component dialog with two composite multifields (Franchise Options, Page Owner Options) |
| `ui.apps/.../components/content-governance-config/content-governance-config.html` | Render script — outputs a visible placeholder in Edit/Preview mode only; no production HTML |

---

## Fields in the Tab

| Field | Type | Stored property | Options source |
|---|---|---|---|
| Franchise | Select (required) | `./franchise` | `content-governance-config` component |
| Page Owners | Select (required) | `./pageOwners` | `content-governance-config` component |
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

## How the DataSource Servlet Works

1. Granite UI renders the `<select>` field and fires a GET request to the datasource resource type
2. Sling resolves the resource type (`mysite/datasource/content-governance-franchise` or `mysite/datasource/content-governance-page-owners`) and dispatches to `ContentGovernanceOptionsDataSourceServlet`
3. The servlet checks `request.getResource().getResourceType()` to determine whether to read `franchiseOptions` or `pageOwnerOptions`
4. It reads the current page path from the `item` request parameter (automatically set by the page properties dialog), falling back to the request suffix
5. It derives the **site root** as the first three path segments — e.g. `/content/mysite/us/en/some/page` → `/content/mysite/us`
6. It runs a JCR SQL-2 query to find the first node with `sling:resourceType = mysite/components/content-governance-config` anywhere under that site root
7. It iterates the multifield child nodes, reading the `key` and `label` properties from each row
8. It returns a `SimpleDataSource` of `ValueMapResource` entries (`value=key`, `text=label`), which Granite UI renders as `<option>` elements

---

## Setting Up the Config Component

1. In AEM Sites, navigate to a page under your locale root (e.g. `/content/mysite/us/en`)
2. Create or open a page designated for governance configuration (e.g. `/content/mysite/us/en/governance-config`)
3. Add the **Content Governance Config** component (group: `My Site - Structure`) to the page
4. Open the component dialog and add rows to both multifields:

   **Franchise Options**
   - Key: `retail` / Label: `Retail`
   - Key: `corporate` / Label: `Corporate`

   **Page Owner Options**
   - Key: `borrow-money` / Label: `Borrow and Manage Money`
   - Key: `invest` / Label: `Invest`

5. Save. The dropdowns in Page Properties on any page under `/content/mysite/us` will now show these options.

> **Note:** The `key` is the value stored in page properties. The `label` is only for display in the dialog. Changing a key after pages have been authored will leave existing pages with the old stored value.

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

---

## Key Design Decisions

| Decision | Reason |
|---|---|
| One servlet registered against two resource types | Avoids duplicating nearly identical servlet code; the resource type alone identifies which field is being populated |
| Options authored in JCR via a component, not OSGi config | Site admins can update options in the AEM authoring UI without a code deployment |
| `key` / `label` separation | The stored `key` is stable and machine-readable; the `label` can be changed freely without affecting existing page data |
| Site root scoping (3 path segments) | Allows different option sets per locale while using a single shared servlet |
