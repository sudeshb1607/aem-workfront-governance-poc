/*
 * MySite Batch Workflow wizard controller.
 *
 * Owned entirely under /apps/mysite. Responsibilities:
 *   1. Read the selected item paths from the request (?item=..&item=..).
 *   2. Render the Scope step's item list: a "select all" header checkbox (with an
 *      indeterminate "minus" state) + a checkbox per row, all checked by default.
 *      Titles are resolved via the C2 servlet.
 *   3. Keep hidden <input name="item"> in sync with the CHECKED rows (only selected
 *      paths POST), and make the step invalid (Create disabled + submit blocked)
 *      when nothing is selected.
 *   4. Redirect back to the page the user came from once the workflow has started.
 *
 * A plain (non Coral-table) layout is used on purpose: dynamically injected Coral
 * table rows overlap and swallow checkbox clicks.
 */
(function (document, $) {
    "use strict";

    var ASSETS_CONSOLE = "/assets.html/content/dam";
    var ITEMS_ENDPOINT = "/bin/mysite/batchworkflow/items";
    var FORM_SELECTOR = "form.mysite-batchworkflow-form";
    var SCOPE_SELECTOR = ".mysite-batchworkflow-scope";
    var ROW_CHECK = "mysite-scope-rowcheck";
    var ALL_CHECK = "mysite-scope-allcheck";

    var returnUrl = ASSETS_CONSOLE;

    function computeReturnUrl(items) {
        var ref = document.referrer;
        if (ref && ref.indexOf("/assets.html") !== -1) {
            return ref;
        }
        if (items && items.length) {
            var first = items[0];
            var parent = first.substring(0, first.lastIndexOf("/"));
            if (parent) {
                return "/assets.html" + parent;
            }
        }
        return ASSETS_CONSOLE;
    }

    function getItemsFromUrl() {
        var items = [];
        var query = window.location.search.replace(/^\?/, "");
        query.split("&").forEach(function (pair) {
            if (!pair) {
                return;
            }
            var kv = pair.split("=");
            if (decodeURIComponent(kv[0]) === "item" && kv.length > 1) {
                items.push(decodeURIComponent(kv[1].replace(/\+/g, "%20")));
            }
        });
        return items;
    }

    function leafName(path) {
        return path.substring(path.lastIndexOf("/") + 1);
    }

    function rowCheckboxes(scope) {
        return scope.querySelectorAll(".mysite-scope-item[data-path] coral-checkbox");
    }

    // Rebuild the hidden item inputs so only the CHECKED rows POST with the form.
    function syncSelection(form, scope) {
        form.querySelectorAll('input[name="item"]').forEach(function (input) {
            input.remove();
        });
        rowCheckboxes(scope).forEach(function (cb) {
            if (cb.checked) {
                var row = cb.closest(".mysite-scope-item[data-path]");
                var input = document.createElement("input");
                input.type = "hidden";
                input.name = "item";
                input.value = row.getAttribute("data-path");
                form.appendChild(input);
            }
        });
    }

    // Reflect the row selection on the "select all" header checkbox: checked / cleared /
    // indeterminate ("minus") when only some rows are selected.
    function updateHeaderState(scope) {
        var header = scope.querySelector("coral-checkbox." + ALL_CHECK);
        if (!header) {
            return;
        }
        var boxes = rowCheckboxes(scope);
        var total = boxes.length;
        var checked = 0;
        boxes.forEach(function (c) { if (c.checked) { checked++; } });

        if (checked === 0) {
            header.indeterminate = false;
            header.checked = false;
        } else if (checked === total) {
            header.indeterminate = false;
            header.checked = true;
        } else {
            header.checked = false;
            header.indeterminate = true;
        }
    }

    // The Scope step carries an off-screen "guard" field holding the number of checked
    // rows. A validator marks it (and therefore the step) invalid when that count is 0,
    // so the wizard itself disables "Create" and blocks submission -- the reliable way,
    // since directly toggling the button gets undone by the wizard's own validation pass.
    function registerGuardValidator() {
        if (window.mysiteBatchGuardValidator) {
            return;
        }
        var registry = $(window).adaptTo("foundation-registry");
        if (!registry) {
            return;
        }
        window.mysiteBatchGuardValidator = true;
        registry.register("foundation.validation.validator", {
            selector: ".mysite-batchworkflow-guard",
            validate: function (el) {
                var count = parseInt(el.value, 10);
                if (!count || count <= 0) {
                    return Granite.I18n.get("Select at least one item to start the workflow.");
                }
                return undefined;
            }
        });
    }

    function updateGuard(form, scope) {
        var count = 0;
        rowCheckboxes(scope).forEach(function (c) { if (c.checked) { count++; } });
        var guard = form.querySelector(".mysite-batchworkflow-guard");
        if (guard) {
            guard.value = String(count);
            // Re-validate the guard so the wizard re-evaluates the step and toggles Create.
            $(guard).trigger("change").trigger("foundation-field-change");
        }
    }

    function checkbox(cls) {
        var cb = document.createElement("coral-checkbox");
        cb.className = cls;
        cb.setAttribute("checked", "");
        cb.checked = true;
        return cb;
    }

    function cell(cls, text) {
        var span = document.createElement("span");
        span.className = cls;
        if (text !== undefined) {
            span.textContent = text;
        }
        return span;
    }

    function renderScope($scope, items, form) {
        var scope = $scope[0];
        scope.innerHTML = "";

        var table = document.createElement("div");
        table.className = "mysite-scope";

        var head = document.createElement("div");
        head.className = "mysite-scope-row mysite-scope-header";
        var headCheckCell = cell("mysite-scope-check");
        headCheckCell.appendChild(checkbox(ALL_CHECK));
        head.appendChild(headCheckCell);
        head.appendChild(cell("mysite-scope-title", Granite.I18n.get("Title")));
        head.appendChild(cell("mysite-scope-path", Granite.I18n.get("Path")));
        table.appendChild(head);

        items.forEach(function (path) {
            var row = document.createElement("div");
            row.className = "mysite-scope-row mysite-scope-item";
            row.setAttribute("data-path", path);

            var checkCell = cell("mysite-scope-check");
            checkCell.appendChild(checkbox(ROW_CHECK));
            row.appendChild(checkCell);
            row.appendChild(cell("mysite-scope-title", leafName(path)));
            row.appendChild(cell("mysite-scope-path", path));
            table.appendChild(row);
        });

        scope.appendChild(table);

        syncSelection(form, scope);
        updateHeaderState(scope);
        updateGuard(form, scope);

        if (items.length === 0) {
            return;
        }
        var params = items.map(function (p) { return "item=" + encodeURIComponent(p); }).join("&");
        $.getJSON(ITEMS_ENDPOINT + "?" + params).done(function (data) {
            (data.items || []).forEach(function (rowData) {
                if (!rowData.title) {
                    return;
                }
                var titleCell = scope.querySelector('.mysite-scope-item[data-path="' + rowData.path.replace(/"/g, '\\"') + '"] .mysite-scope-title');
                if (titleCell) {
                    titleCell.textContent = rowData.title;
                }
            });
        });
    }

    $(document).on("foundation-contentloaded", function (e) {
        var $form = $(e.target).find(FORM_SELECTOR);
        if (!$form.length || $form.data("mysite-batch-init")) {
            return;
        }
        $form.data("mysite-batch-init", true);
        var form = $form[0];

        registerGuardValidator();

        var items = getItemsFromUrl();
        returnUrl = computeReturnUrl(items);

        var $scope = $form.find(SCOPE_SELECTOR);
        if ($scope.length) {
            window.setTimeout(function () { renderScope($scope, items, form); }, 0);
        }

        // A row checkbox toggled -> update hidden inputs, header state and validity.
        $form.on("change", "coral-checkbox." + ROW_CHECK, function () {
            var scope = $form.find(SCOPE_SELECTOR)[0];
            syncSelection(form, scope);
            updateHeaderState(scope);
            updateGuard(form, scope);
        });

        // "Select all" toggled -> apply to every row, then re-sync.
        $form.on("change", "coral-checkbox." + ALL_CHECK, function () {
            var scope = $form.find(SCOPE_SELECTOR)[0];
            var checked = this.checked;
            rowCheckboxes(scope).forEach(function (cb) { cb.checked = checked; });
            syncSelection(form, scope);
            updateHeaderState(scope);
            updateGuard(form, scope);
        });
    });

    // Redirect back to where the user came from once the workflow started.
    $(document).on("foundation-form-submitted", FORM_SELECTOR, function (e, data, xhr) {
        var status = xhr && xhr.status;
        if (!status || (status >= 200 && status < 400)) {
            window.location.href = returnUrl;
        }
    });

}(document, Granite.$));
