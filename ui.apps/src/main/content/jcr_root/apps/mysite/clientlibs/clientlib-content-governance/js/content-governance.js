/*
 * Content Governance — Franchise → Page Owners cascading dropdowns.
 *
 * The Page Properties "Content Governance" tab has a Franchise select
 * (.cg-franchise, populated by a Granite datasource) and a Page Owners select
 * (.cg-page-owners, populated entirely here). Page Owners are scoped per
 * Franchise via a nested multifield on the content-governance-config component,
 * so this controller:
 *   1. On dialog load, fetches the owners for the saved Franchise and re-selects
 *      the saved owner if it still exists.
 *   2. On Franchise change, refetches owners, repopulates the select, clears the
 *      stale selection and re-triggers Granite validation.
 *
 * Endpoint: /bin/mysite/content-governance/page-owners.json?item=<path>&franchise=<key>
 */
(function (document, $) {
    "use strict";

    var OWNERS_ENDPOINT = "/bin/mysite/content-governance/page-owners.json";
    var FRANCHISE_SELECTOR = ".cg-franchise";
    var OWNERS_SELECTOR = ".cg-page-owners";

    // Read the edited page path from the properties URL (?item=/content/...).
    function getItemPath() {
        var query = window.location.search.replace(/^\?/, "");
        var path = null;
        query.split("&").forEach(function (pair) {
            if (!pair) {
                return;
            }
            var kv = pair.split("=");
            if (decodeURIComponent(kv[0]) === "item" && kv.length > 1) {
                path = decodeURIComponent(kv[1].replace(/\+/g, "%20"));
            }
        });
        return path;
    }

    // Coral Select value: read from the component API when available, else the DOM value.
    function selectValue(el) {
        if (el && typeof el.value !== "undefined") {
            return el.value;
        }
        return null;
    }

    // Rebuild the Coral Select's items from the endpoint payload, optionally
    // re-selecting a previously-saved owner value if it still exists.
    function populateOwners($owners, owners, selectedValue) {
        var select = $owners[0];
        if (!select) {
            return;
        }

        // Clear existing options via the Coral collection API (falls back to innerHTML).
        if (select.items && typeof select.items.clear === "function") {
            select.items.clear();
        } else {
            select.innerHTML = "";
        }

        var hasSelected = false;
        owners.forEach(function (owner) {
            var isSelected = selectedValue && owner.value === selectedValue;
            if (isSelected) {
                hasSelected = true;
            }
            if (select.items && typeof select.items.add === "function") {
                select.items.add({
                    value: owner.value,
                    content: { textContent: owner.text },
                    selected: isSelected
                });
            } else {
                var opt = new Option(owner.text, owner.value, isSelected, isSelected);
                select.appendChild(opt);
            }
        });

        if (!hasSelected && typeof select.value !== "undefined") {
            select.value = "";
        }

        // Keep Granite validation in sync with the rebuilt field.
        $owners.trigger("change").trigger("foundation-field-change");
    }

    function loadOwners($franchise, $owners, itemPath, selectedValue) {
        var franchise = selectValue($franchise[0]);
        if (!itemPath || !franchise) {
            populateOwners($owners, [], null);
            return;
        }
        $.getJSON(OWNERS_ENDPOINT, { item: itemPath, franchise: franchise })
            .done(function (owners) {
                populateOwners($owners, owners || [], selectedValue);
            })
            .fail(function () {
                populateOwners($owners, [], null);
            });
    }

    $(document).on("foundation-contentloaded", function (e) {
        var $root = $(e.target);
        var $franchise = $root.find(FRANCHISE_SELECTOR);
        var $owners = $root.find(OWNERS_SELECTOR);
        if (!$franchise.length || !$owners.length) {
            return;
        }
        if ($owners.data("cg-init")) {
            return;
        }
        $owners.data("cg-init", true);

        var itemPath = getItemPath();
        // The saved owner value is the select's initial value before we rebuild it.
        var savedOwner = selectValue($owners[0]);

        // Initial population from the saved franchise; re-select the saved owner.
        window.setTimeout(function () {
            loadOwners($franchise, $owners, itemPath, savedOwner);
        }, 0);

        // On franchise change, refetch and clear the previous selection.
        $franchise.on("change", function () {
            loadOwners($franchise, $owners, itemPath, null);
        });
    });

}(document, Granite.$));
