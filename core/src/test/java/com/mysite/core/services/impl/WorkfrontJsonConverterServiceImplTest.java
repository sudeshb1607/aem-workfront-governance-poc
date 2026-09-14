package com.mysite.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import javax.json.JsonObject;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.junit.jupiter.api.Test;

import com.day.cq.dam.api.Asset;
import com.day.cq.dam.api.AssetManager;
import com.day.cq.dam.api.Rendition;
import com.mysite.core.services.WorkfrontJsonConverterService.ConversionResult;

/**
 * Exercises the pure CSV -> JSON conversion ({@link WorkfrontJsonConverterServiceImpl#buildDataset})
 * plus the full {@code convert()} DAM path (with a mocked asset + AssetManager).
 */
class WorkfrontJsonConverterServiceImplTest {

    private static final String HEADER = "Hash,Title,Path,Brand,Last Modified,Modified By,Published,"
            + "Next Review Date,Days For Next Review,Franchise,Page Owners,Template\r\n";

    private final WorkfrontJsonConverterServiceImpl service = new WorkfrontJsonConverterServiceImpl();

    @Test
    void convertWritesJsonAssetIntoTargetFolder() throws Exception {
        final String csv = HEADER
                + "h1,Home,/content/natwest/en,natwest,2026-07-30,admin,True,,,,,\r\n";

        final Rendition original = mock(Rendition.class);
        when(original.getStream()).thenReturn(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
        final Asset asset = mock(Asset.class);
        when(asset.getOriginal()).thenReturn(original);

        final AssetManager assetManager = mock(AssetManager.class);
        final ResourceResolver resolver = mock(ResourceResolver.class);
        when(resolver.adaptTo(AssetManager.class)).thenReturn(assetManager);

        final Resource csvAsset = mock(Resource.class);
        when(csvAsset.getName()).thenReturn("expiring-published-natwest.csv");
        when(csvAsset.getPath()).thenReturn("/content/dam/mysite/workfront-reports/expiring-published/csv/expiring-published-natwest.csv");
        when(csvAsset.adaptTo(Asset.class)).thenReturn(asset);
        when(csvAsset.getResourceResolver()).thenReturn(resolver);

        final ConversionResult result = service.convert(csvAsset,
                "/content/dam/mysite/workfront-reports/expiring-published/json");

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals(1, result.getRecordCount());
        assertEquals("/content/dam/mysite/workfront-reports/expiring-published/json/expiring-published-natwest.json",
                result.getJsonPath());
        verify(assetManager).createAsset(
                eq("/content/dam/mysite/workfront-reports/expiring-published/json/expiring-published-natwest.json"),
                any(), eq("application/json"), eq(true));
        verify(resolver).commit();
    }

    @Test
    void convertFailsGracefullyForNonAsset() {
        final Resource csvAsset = mock(Resource.class);
        when(csvAsset.getName()).thenReturn("x.csv");
        when(csvAsset.getPath()).thenReturn("/content/dam/x.csv");
        when(csvAsset.adaptTo(Asset.class)).thenReturn(null);

        final ConversionResult result = service.convert(csvAsset, "/content/dam/json");
        assertFalse(result.isSuccess());
    }

    @Test
    void convertFailsWhenOriginalRenditionMissing() {
        final Asset asset = mock(Asset.class);
        when(asset.getOriginal()).thenReturn(null);
        final Resource csvAsset = mock(Resource.class);
        when(csvAsset.getName()).thenReturn("x.csv");
        when(csvAsset.getPath()).thenReturn("/content/dam/x.csv");
        when(csvAsset.adaptTo(Asset.class)).thenReturn(asset);

        final ConversionResult result = service.convert(csvAsset, "/content/dam/json");
        assertFalse(result.isSuccess());
    }

    @Test
    void singleArgConvertUsesConfiguredOutputFolder() throws Exception {
        final WorkfrontJsonConverterServiceImpl configured = new WorkfrontJsonConverterServiceImpl();
        final WorkfrontJsonConverterServiceImpl.Config cfg = mock(WorkfrontJsonConverterServiceImpl.Config.class);
        when(cfg.inputFolder()).thenReturn("/content/dam/in");
        when(cfg.outputFolder()).thenReturn("/content/dam/out");
        configured.activate(cfg);
        assertEquals("/content/dam/in", configured.getInputFolder());
        assertEquals("/content/dam/out", configured.getOutputFolder());

        final Rendition original = mock(Rendition.class);
        when(original.getStream()).thenReturn(new ByteArrayInputStream((HEADER
                + "h1,T,/content/a,brandx,,,False,,,,,\r\n").getBytes(StandardCharsets.UTF_8)));
        final Asset asset = mock(Asset.class);
        when(asset.getOriginal()).thenReturn(original);
        final AssetManager am = mock(AssetManager.class);
        final ResourceResolver resolver = mock(ResourceResolver.class);
        when(resolver.adaptTo(AssetManager.class)).thenReturn(am);
        final Resource csvAsset = mock(Resource.class);
        when(csvAsset.getName()).thenReturn("all-live-brandx.csv");
        when(csvAsset.getPath()).thenReturn("/content/dam/in/all-live-brandx.csv");
        when(csvAsset.adaptTo(Asset.class)).thenReturn(asset);
        when(csvAsset.getResourceResolver()).thenReturn(resolver);

        final ConversionResult result = configured.convert(csvAsset);
        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals("/content/dam/out/all-live-brandx.json", result.getJsonPath());
    }

    @Test
    void convertsCsvToJsonWithTypedFields() {
        final String csv = HEADER
                + "abc,\"Home, sweet\",/content/mysite/us/en,mysite,2026-07-30,jdoe,True,"
                + "2026-12-31,114,retail,borrow,/conf/mysite/page\r\n";

        final JsonObject payload = service.buildDataset("us-en", csv);
        assertEquals("us-en", payload.getString("dataset"));
        assertEquals(1, payload.getInt("recordCount"));
        assertTrue(payload.containsKey("generatedAt"));

        final JsonObject record = payload.getJsonArray("records").getJsonObject(0);
        assertEquals("abc", record.getString("hash"));
        assertEquals("Home, sweet", record.getString("title"));
        assertEquals("/content/mysite/us/en", record.getString("path"));
        assertEquals("mysite", record.getString("brand"));
        assertTrue(record.getBoolean("published"), "Published should be a real boolean");
        assertEquals(114, record.getInt("daysForNextReview"), "Days should be a real int");
        assertEquals("retail", record.getString("franchise"));
        assertEquals("borrow", record.getString("pageOwners"));
    }

    @Test
    void blankReviewDateProducesNullDaysAndFalsePublished() {
        final String csv = HEADER
                + "h1,Root,/content/mysite,mysite,2026-07-30,admin,False,,,,,\r\n";

        final JsonObject record = service.buildDataset("root", csv)
                .getJsonArray("records").getJsonObject(0);
        assertFalse(record.getBoolean("published"));
        assertTrue(record.isNull("daysForNextReview"), "Blank days should be JSON null");
    }

    @Test
    void nonIntegerDaysProducesNull() {
        final String csv = HEADER
                + "h1,Root,/content/mysite,mysite,2026-07-30,admin,True,2026-12-31,notanumber,,,\r\n";

        final JsonObject record = service.buildDataset("root", csv)
                .getJsonArray("records").getJsonObject(0);
        assertTrue(record.isNull("daysForNextReview"));
    }

    @Test
    void skipsRowsWithoutPath() {
        final String csv = HEADER
                + "h1,Valid,/content/mysite/a,mysite,2026-07-30,admin,True,,,,,\r\n"
                + "h2,NoPath,,mysite,2026-07-30,admin,True,,,,,\r\n";

        final JsonObject payload = service.buildDataset("mixed", csv);
        assertEquals(1, payload.getInt("recordCount"), "Row without a Path must be skipped");
    }

    @Test
    void emptyCsvProducesZeroRecords() {
        assertEquals(0, service.buildDataset("empty", HEADER).getInt("recordCount"));
    }
}
