/*
 * Content Governance — Franchise → Page Owners cascading dropdowns.
 *
 * The Page Properties "Content Governance" tab has a Franchise select
 * (.cg-franchise, populated by a Granite datasource) and a Page Owners select
 * (.cg-page-owners, populated entirely here). Page Owners are scoped per
 * Franchise via a nested multifield on the content-governance-config component,
 * so this controller:
 *   1. Keeps Page Owners hidden until a Franchise is chosen; it appears once a
 *      Franchise is selected and hides again if the Franchise is cleared.
 *   2. On dialog load, fetches the owners for the saved Franchise and re-selects
 *      the saved owner if it still exists.
 *   3. On Franchise change, refetches owners, repopulates the select, clears the
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

    // Show/hide the Page Owners field wrapper (label + control) so it only appears
    // once a Franchise is chosen. Hidden Granite fields are skipped by validation,
    // so the required Page Owners field won't block save while no Franchise is set.
    function toggleOwners($owners, visible) {
        var $wrapper = $owners.closest(".coral-Form-fieldwrapper");
        if (!$wrapper.length) {
            $wrapper = $owners;
        }
        $wrapper.toggleClass("hide", !visible);
        if (!visible) {
            $wrapper.hide();
        } else {
            $wrapper.show();
        }
    }

    function loadOwners($franchise, $owners, itemPath, selectedValue) {
        var franchise = selectValue($franchise[0]);
        if (!itemPath || !franchise) {
            populateOwners($owners, [], null);
            toggleOwners($owners, false);
            return;
        }
        toggleOwners($owners, true);
        $.getJSON(OWNERS_ENDPOINT, { item: itemPath, franchise: franchise })
            .done(function (owners) {
                populateOwners($owners, owners || [], selectedValue);
            })
            .fail(function () {
                populateOwners($owners, [], null);
            });
    }

    // -----------------------------------------------------------------------
    // Multifield "Add" scroll fix.
    //
    // Coral 3 composite/nested multifields call scrollIntoView() on the newly
    // added item, which yanks the scrollable dialog body to the bottom every
    // time the author clicks an "Add" button. Capture the scroll position of the
    // affected scroll container(s) on the Add-click and restore it once Coral has
    // finished inserting the row.
    // -----------------------------------------------------------------------
    function scrollParents(el) {
        var parents = [];
        var node = el ? el.parentElement : null;
        while (node) {
            var oy = window.getComputedStyle(node).overflowY;
            if ((oy === "auto" || oy === "scroll") && node.scrollHeight > node.clientHeight) {
                parents.push(node);
            }
            node = node.parentElement;
        }
        var docEl = document.scrollingElement || document.documentElement;
        if (docEl && parents.indexOf(docEl) === -1) {
            parents.push(docEl);
        }
        return parents;
    }

    $(document).on("click", "coral-multifield [coral-multifield-add], coral-multifield button[coral-multifield-add]", function () {
        var targets = scrollParents(this).map(function (node) {
            return { node: node, top: node.scrollTop };
        });
        function restore() {
            targets.forEach(function (t) { t.node.scrollTop = t.top; });
        }
        // Restore across the frames where Coral inserts the row and focuses it.
        window.requestAnimationFrame(function () {
            restore();
            window.requestAnimationFrame(restore);
        });
        window.setTimeout(restore, 0);
    });

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

        // Hide up-front to avoid a flash; loadOwners re-shows it if a Franchise is set.
        toggleOwners($owners, false);

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
