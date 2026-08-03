/*
 * Batch Workflow action-bar visibility controller (loaded into the Assets console
 * via the dam.gui.actions.coral clientlib category).
 *
 * The DAM console stamps custom collection actions with
 * "foundation-collection-action-hidden" (display:none) and only its own OOTB
 * activators un-hide them. Our action has no such activator, so it stays hidden.
 * This controller un-hides the "Batch Workflow" action whenever 2+ items are
 * selected, and re-hides it otherwise -- giving the exact "enabled on multi-select"
 * behaviour without touching any OOTB node.
 */
(function (document, $) {
    "use strict";

    var ACTION_SELECTOR = ".mysite-batchworkflow-action";
    var HIDDEN_CLASS = "foundation-collection-action-hidden";
    var MIN_SELECTION = 2;

    function selectedCount() {
        return document.querySelectorAll('.foundation-collection-item[aria-selected="true"]').length;
    }

    function sync() {
        var action = document.querySelector(ACTION_SELECTOR);
        if (!action) {
            return;
        }
        if (selectedCount() >= MIN_SELECTION) {
            action.classList.remove(HIDDEN_CLASS);
        } else {
            action.classList.add(HIDDEN_CLASS);
        }
    }

    // Run after the OOTB selection handlers (which may re-add the hidden class).
    function syncDeferred() {
        window.setTimeout(sync, 0);
    }

    $(document).on("foundation-selections-change", syncDeferred);
    $(document).on("foundation-contentloaded", syncDeferred);

}(document, Granite.$));
