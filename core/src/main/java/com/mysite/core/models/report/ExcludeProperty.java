/*
 *  Copyright 2024 Adobe Systems Incorporated
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.mysite.core.models.report;

import org.apache.sling.api.resource.ValueMap;

/**
 * Immutable value object describing an exclude-property condition: a page is
 * omitted from the report when the named property (read from {@code jcr:content}
 * first, then the page node) equals the configured value. Populated from the
 * single optional {@code excludePropertyName} / {@code excludePropertyValue}
 * fields on a report component.
 */
public final class ExcludeProperty {

    private final String name;
    private final String value;

    public ExcludeProperty(final String name, final String value) {
        this.name = name;
        this.value = value;
    }

    /**
     * @return the property name to test (e.g. {@code excludeFromDelete}).
     */
    public String getName() {
        return name;
    }

    /**
     * @return the value that, when matched, excludes the page (e.g. {@code true}).
     */
    public String getValue() {
        return value;
    }

    /**
     * Tests whether this exclusion condition matches: the named property, read
     * from {@code jcr:content} first and then the page node, equals the configured
     * value. Shared by the report engine and the per-report filters so the rule
     * lives in one place.
     *
     * @param contentVm the {@code jcr:content} value map (never {@code null}; use
     *                  {@link ValueMap#EMPTY} when there is no content node)
     * @param pageVm    the page node value map
     * @return {@code true} when the property equals the configured value
     */
    public boolean matches(final ValueMap contentVm, final ValueMap pageVm) {
        final Object actual = contentVm.containsKey(name)
                ? contentVm.get(name)
                : (pageVm.containsKey(name) ? pageVm.get(name) : null);
        return actual != null && value.equals(String.valueOf(actual));
    }

    @Override
    public String toString() {
        return "ExcludeProperty{name='" + name + "', value='" + value + "'}";
    }
}
