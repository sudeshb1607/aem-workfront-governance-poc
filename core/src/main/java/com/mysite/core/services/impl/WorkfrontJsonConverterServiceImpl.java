package com.mysite.core.services.impl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.dam.api.Asset;
import com.day.cq.dam.api.AssetManager;
import com.day.cq.dam.api.Rendition;
import com.mysite.core.services.WorkfrontJsonConverterService;
import com.mysite.core.util.SimpleCsvParser;

/**
 * Default {@link WorkfrontJsonConverterService}. Reads a CSV asset's original
 * rendition, parses it with {@link SimpleCsvParser}, and writes a JSON dataset
 * asset (via {@link AssetManager}) into the configured output folder using the
 * caller-supplied resolver. Malformed rows are skipped (logged) rather than
 * failing the whole file.
 */
@Designate(ocd = WorkfrontJsonConverterServiceImpl.Config.class)
@Component(service = WorkfrontJsonConverterService.class)
public class WorkfrontJsonConverterServiceImpl implements WorkfrontJsonConverterService {

    private static final Logger LOG = LoggerFactory.getLogger(WorkfrontJsonConverterServiceImpl.class);

    private static final String CSV_EXTENSION = ".csv";
    private static final String JSON_EXTENSION = ".json";
    private static final String JSON_MIME_TYPE = "application/json";

    // CSV column headers (must match the report engine's default columns; see ReportsConstants.defaultColumns()).
    private static final String COL_HASH = "Hash";
    private static final String COL_TITLE = "Title";
    private static final String COL_PATH = "Path";
    private static final String COL_BRAND = "Brand";
    private static final String COL_LAST_MODIFIED = "Last Modified";
    private static final String COL_MODIFIED_BY = "Modified By";
    private static final String COL_PUBLISHED = "Published";
    private static final String COL_NEXT_REVIEW_DATE = "Next Review Date";
    private static final String COL_DAYS_FOR_NEXT_REVIEW = "Days For Next Review";
    private static final String COL_FRANCHISE = "Franchise";
    private static final String COL_PAGE_OWNERS = "Page Owners";
    private static final String COL_TEMPLATE = "Template";

    @ObjectClassDefinition(
            name = "Workfront JSON Converter Service",
            description = "Converts Workfront dashboard CSV exports into JSON datasets for the Fusion webhook.")
    public @interface Config {

        @AttributeDefinition(name = "Input folder",
                description = "DAM folder scanned for source CSV files.")
        String inputFolder() default "/content/dam/mysite/workfront-dashboard";

        @AttributeDefinition(name = "Output folder",
                description = "DAM folder the generated JSON datasets are written into.")
        String outputFolder() default "/content/dam/mysite/workfront-dashboard/json";
    }

    private String inputFolder;
    private String outputFolder;

    @Activate
    protected void activate(final Config config) {
        this.inputFolder = StringUtils.removeEnd(
                StringUtils.defaultString(config.inputFolder(), "").trim(), "/");
        this.outputFolder = StringUtils.removeEnd(
                StringUtils.defaultString(config.outputFolder(), "").trim(), "/");
        LOG.info("WorkfrontJsonConverterService activated. inputFolder={}, outputFolder={}",
                inputFolder, outputFolder);
    }

    @Override
    public String getInputFolder() {
        return inputFolder;
    }

    @Override
    public String getOutputFolder() {
        return outputFolder;
    }

    @Override
    public ConversionResult convert(final Resource csvAsset) {
        return convert(csvAsset, outputFolder);
    }

    @Override
    public ConversionResult convert(final Resource csvAsset, final String jsonOutputFolder) {
        final String targetFolder = StringUtils.removeEnd(
                StringUtils.defaultString(jsonOutputFolder, outputFolder).trim(), "/");
        final String csvName = StringUtils.removeEndIgnoreCase(csvAsset.getName(), CSV_EXTENSION);
        final String jsonPath = targetFolder + "/" + csvName + JSON_EXTENSION;
        try {
            final Asset asset = csvAsset.adaptTo(Asset.class);
            if (asset == null) {
                return ConversionResult.failure(jsonPath, "Not a DAM asset: " + csvAsset.getPath());
            }

            final String csvText = readOriginal(asset);
            final JsonObject payload = buildDataset(csvName, csvText);
            final int recordCount = payload.getInt("recordCount");

            writeAsset(csvAsset.getResourceResolver(), jsonPath, payload.toString());
            LOG.info("Converted {} -> {} ({} record(s))", csvAsset.getPath(), jsonPath, recordCount);
            return ConversionResult.success(jsonPath, recordCount);
        } catch (final Exception e) {
            LOG.error("Failed to convert {} to JSON at {}", csvAsset.getPath(), jsonPath, e);
            return ConversionResult.failure(jsonPath, e.getMessage());
        }
    }

    /**
     * Parses CSV text and builds the self-describing dataset object
     * ({@code dataset}, {@code generatedAt}, {@code recordCount}, {@code records[]}).
     * Package-private so the pure conversion can be unit-tested without DAM I/O.
     */
    JsonObject buildDataset(final String datasetName, final String csvText) {
        final List<Map<String, String>> rows = SimpleCsvParser.parse(csvText);
        LOG.debug("Parsed {} data row(s) for dataset '{}'", rows.size(), datasetName);
        final JsonArray records = buildRecords(datasetName, rows);
        return Json.createObjectBuilder()
                .add("dataset", datasetName)
                .add("generatedAt", Instant.now().toString())
                .add("recordCount", records.size())
                .add("records", records)
                .build();
    }

    /**
     * Builds the {@code records} array, re-typing {@code Published} to boolean and
     * {@code Days For Next Review} to an integer. Rows without a {@code Path} are
     * treated as malformed and skipped with a warning.
     */
    private JsonArray buildRecords(final String csvName, final List<Map<String, String>> rows) {
        final JsonArrayBuilder arr = Json.createArrayBuilder();
        for (final Map<String, String> row : rows) {
            final String path = trim(row.get(COL_PATH));
            if (StringUtils.isBlank(path)) {
                LOG.warn("Skipping malformed row (no Path) in dataset '{}'", csvName);
                continue;
            }
            final JsonObjectBuilder o = Json.createObjectBuilder()
                    .add("hash", nz(row.get(COL_HASH)))
                    .add("title", nz(row.get(COL_TITLE)))
                    .add("path", path)
                    .add("brand", nz(row.get(COL_BRAND)))
                    .add("lastModified", nz(row.get(COL_LAST_MODIFIED)))
                    .add("modifiedBy", nz(row.get(COL_MODIFIED_BY)))
                    .add("published", "true".equalsIgnoreCase(trim(row.get(COL_PUBLISHED))))
                    .add("nextReviewDate", nz(row.get(COL_NEXT_REVIEW_DATE)));
            addInt(o, "daysForNextReview", row.get(COL_DAYS_FOR_NEXT_REVIEW), csvName);
            o.add("franchise", nz(row.get(COL_FRANCHISE)))
                    .add("pageOwners", nz(row.get(COL_PAGE_OWNERS)))
                    .add("template", nz(row.get(COL_TEMPLATE)));
            arr.add(o);
        }
        return arr.build();
    }

    private static void addInt(final JsonObjectBuilder o, final String key,
                               final String rawValue, final String csvName) {
        final String value = trim(rawValue);
        if (StringUtils.isBlank(value)) {
            o.addNull(key);
            return;
        }
        try {
            o.add(key, Integer.parseInt(value));
        } catch (final NumberFormatException e) {
            LOG.warn("Non-integer '{}' for {} in dataset '{}'; emitting null", value, key, csvName);
            o.addNull(key);
        }
    }

    private static String readOriginal(final Asset asset) throws Exception {
        final Rendition original = asset.getOriginal();
        if (original == null) {
            throw new IllegalStateException("Asset has no original rendition: " + asset.getPath());
        }
        try (InputStream in = original.getStream()) {
            if (in == null) {
                throw new IllegalStateException("Could not read original rendition of " + asset.getPath());
            }
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                bos.write(buffer, 0, read);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void writeAsset(final ResourceResolver resolver, final String jsonPath,
                                   final String jsonContent) throws Exception {
        final AssetManager assetManager = resolver.adaptTo(AssetManager.class);
        if (assetManager == null) {
            throw new IllegalStateException("Could not adapt ResourceResolver to AssetManager");
        }
        try (InputStream in = new ByteArrayInputStream(jsonContent.getBytes(StandardCharsets.UTF_8))) {
            assetManager.createAsset(jsonPath, in, JSON_MIME_TYPE, true);
        }
        resolver.commit();
    }

    private static String trim(final String value) {
        return value == null ? null : value.trim();
    }

    private static String nz(final String value) {
        return value == null ? "" : value;
    }
}
