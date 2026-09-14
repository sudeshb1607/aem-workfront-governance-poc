package com.mysite.core.models.impl;

import javax.annotation.PostConstruct;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mysite.core.models.ReportConfigModel;
import com.mysite.core.reports.ReportDefinition;
import com.mysite.core.reports.ReportDefinitionReader;
import com.mysite.core.reports.ReportType;

/**
 * Default {@link ReportConfigModel}. Resolves the report {@link ReportDefinition}
 * (with defaults applied) from the component resource so the HTL summary can list
 * the effective configuration and render a "Generate now" button.
 */
@Model(adaptables = Resource.class, adapters = ReportConfigModel.class)
public class ReportConfigModelImpl implements ReportConfigModel {

    private static final Logger LOG = LoggerFactory.getLogger(ReportConfigModelImpl.class);

    private final Resource resource;

    private ReportType type;
    private ReportDefinition definition;

    public ReportConfigModelImpl(final Resource resource) {
        this.resource = resource;
    }

    @PostConstruct
    protected void init() {
        this.type = ReportType.fromResourceType(resource.getResourceType());
        if (type == null) {
            LOG.debug("Resource {} is not a report component", resource.getPath());
            return;
        }
        try {
            this.definition = ReportDefinitionReader.readOne(resource);
        } catch (final Exception e) {
            LOG.warn("Could not read report definition at {}", resource.getPath(), e);
        }
    }

    @Override
    public boolean isValid() {
        return definition != null;
    }

    @Override
    public String getComponentPath() {
        return resource.getPath();
    }

    @Override
    public String getReportId() {
        return type != null ? type.getReportId() : "";
    }

    @Override
    public ReportDefinition getDefinition() {
        return definition;
    }

    @Override
    public String getRuleSummary() {
        if (type == null || definition == null) {
            return "";
        }
        switch (type) {
            case ALL_LIVE:
                return "All live (published) pages under the configured paths.";
            case EXPIRING_PUBLISHED:
                return "Published pages expiring within " + definition.getThresholdDays()
                        + " day(s), including already-expired.";
            case NOT_LIVE_STALE:
                return "Not-live pages not modified in the last " + definition.getThresholdMonths()
                        + " month(s), without the exclusion flag.";
            case LIVE_LONG_NO_CHILDREN:
                return "Live pages last published over " + definition.getThresholdMonths()
                        + " month(s) ago, with no child page and no exclusion flag.";
            case ARCHIVE_AGED:
                return "Pages in the configured archive folders aged between "
                        + definition.getArchiveMinDays() + " and " + definition.getArchiveMaxDays()
                        + " day(s) (by " + definition.getArchiveDateProp() + ").";
            default:
                return "";
        }
    }
}
