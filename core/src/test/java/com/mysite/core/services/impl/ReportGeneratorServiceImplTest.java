package com.mysite.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.jcr.Session;

import com.day.cq.commons.Externalizer;
import com.day.cq.dam.api.AssetManager;
import com.day.cq.replication.ReplicationActionType;
import com.day.cq.replication.Replicator;
import com.mysite.core.reports.ReportDefinition;
import com.mysite.core.reports.ReportDefinitionReader;
import com.mysite.core.reports.ReportFilter;
import com.mysite.core.reports.ReportType;
import com.mysite.core.services.ReportGeneratorService.ReportRunResult;
import com.mysite.core.testcontext.AppAemContext;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

/**
 * Covers both the row/exclusion path ({@code appendPageRowIfMatched}) and the full
 * {@code generate()} flow. The DAM write is intercepted by a test subclass so the
 * whole flow (service resolver, QueryBuilder batches, filter, columns, cap,
 * per-brand CSV) runs without AssetManager.
 */
@ExtendWith(AemContextExtension.class)
class ReportGeneratorServiceImplTest {

    private final AemContext context = AppAemContext.newAemContext();
    private final ReportFilter acceptAll = (page, content) -> true;

    /** Captures written CSVs instead of touching the DAM. */
    private static final class CapturingGenerator extends ReportGeneratorServiceImpl {
        final Map<String, String> written = new LinkedHashMap<>();

        @Override
        void writeAsset(final ResourceResolver resolver, final String csvPath, final String csvContent) {
            written.put(csvPath, csvContent);
        }

        @Override
        void replicate(final ResourceResolver resolver, final String csvPath) {
            // no-op in tests
        }
    }

    // ---- appendPageRowIfMatched (no QueryBuilder) --------------------------------

    private ReportDefinition allLiveDefinition(final Object... extraProps) {
        final String base = "/content/cfg/all";
        final Object[] all = concat(new Object[]{"sling:resourceType", ReportType.ALL_LIVE.getResourceType()}, extraProps);
        context.build().resource(base, all).commit();
        return ReportDefinitionReader.readOne(context.resourceResolver().getResource(base));
    }

    private Resource page(final String path, final Object... contentProps) {
        context.build().resource(path, "jcr:primaryType", "cq:Page")
                .resource(path + "/jcr:content", contentProps)
                .commit();
        return context.resourceResolver().getResource(path);
    }

    @Test
    void resolvesDefaultColumnsForAMatchedPage() {
        final ReportDefinition def = allLiveDefinition();
        final Resource page = page("/content/natwest/gb/en",
                "jcr:primaryType", "cq:PageContent", "jcr:title", "Home",
                "franchise", "retail", "cq:template", "/conf/mysite/page");

        final StringBuilder csv = new StringBuilder();
        final boolean appended = new ReportGeneratorServiceImpl()
                .appendPageRowIfMatched(context.resourceResolver(), page, def, acceptAll, csv);

        assertTrue(appended);
        final String row = csv.toString();
        assertTrue(row.contains("Home"), row);
        assertTrue(row.contains("/content/natwest/gb/en"), row);
        assertTrue(row.contains("natwest"), row);
        assertTrue(row.contains("False"), row);
        assertTrue(row.contains("retail"), row);
    }

    @Test
    void skipsPageWhenExcludePropertyMatches() {
        final ReportDefinition def = allLiveDefinition(
                "excludePropertyName", "excludeFromReport", "excludePropertyValue", "true");
        final Resource page = page("/content/natwest/x",
                "jcr:primaryType", "cq:PageContent", "excludeFromReport", "true");
        assertFalse(new ReportGeneratorServiceImpl().appendPageRowIfMatched(
                context.resourceResolver(), page, def, acceptAll, new StringBuilder()));
    }

    @Test
    void doesNotExcludeWhenNoExcludePropertyConfigured() {
        // Empty exclude-property name => no comparison, even if the flag is set on the page.
        final ReportDefinition def = allLiveDefinition();
        final Resource page = page("/content/natwest/y",
                "jcr:primaryType", "cq:PageContent", "excludeFromReport", "true");
        assertTrue(new ReportGeneratorServiceImpl().appendPageRowIfMatched(
                context.resourceResolver(), page, def, acceptAll, new StringBuilder()));
    }

    @Test
    void skipsExcludedPath() {
        final ReportDefinition def = allLiveDefinition("excludePaths", new String[]{"/content/test"});
        final Resource page = page("/content/test/foo", "jcr:primaryType", "cq:PageContent");
        assertFalse(new ReportGeneratorServiceImpl().appendPageRowIfMatched(
                context.resourceResolver(), page, def, acceptAll, new StringBuilder()));
    }

    // ---- full generate() flow ---------------------------------------------------

    private ReportDefinition staleDefinition(final String brand, final String root, final int maxRecords) {
        final String base = "/content/cfg/stale";
        context.build()
                .resource(base, "sling:resourceType", ReportType.NOT_LIVE_STALE.getResourceType(),
                        "thresholdMonths", 6L, "maxRecords", (long) maxRecords,
                        "outputFolder", "/content/dam/mysite/workfront-reports/not-live-stale")
                .resource(base + "/brands/item0", "brand", brand, "rootPath", root)
                .commit();
        return ReportDefinitionReader.readOne(context.resourceResolver().getResource(base));
    }

    private Resource stalePage(final String path, final int monthsAgo) {
        final Calendar cal = Calendar.getInstance();
        cal.add(Calendar.MONTH, -monthsAgo);
        return page(path, "jcr:primaryType", "cq:PageContent", "cq:lastModified", cal, "jcr:title", path);
    }

    /**
     * Wires a capturing generator to the AemContext resolver. The engine now walks
     * the real page hierarchy, so tests just build cq:Page nodes under the brand
     * root and the traversal finds them — no QueryBuilder mocking needed.
     */
    private CapturingGenerator newGenerator(final int pageBatchSize) throws Exception {
        final CapturingGenerator gen = new CapturingGenerator();
        final ResourceResolverFactory rrf = mock(ResourceResolverFactory.class);
        when(rrf.getServiceResourceResolver(any())).thenReturn(context.resourceResolver());
        setField(gen, "resolverFactory", rrf);
        setField(gen, "replicator", mock(Replicator.class));
        setField(gen, "pageBatchSize", pageBatchSize);
        return gen;
    }

    @Test
    void generatesPerBrandCsvForMatchingPages() throws Exception {
        stalePage("/content/natwest/a", 8);
        stalePage("/content/natwest/b", 1);
        stalePage("/content/natwest/c", 9);

        final ReportDefinition def = staleDefinition("natwest", "/content/natwest", 0);
        final CapturingGenerator gen = newGenerator(500);

        final ReportRunResult result = gen.generate(def);

        assertTrue(result.isSuccess(), result.getErrorMessage());
        final String csvPath = "/content/dam/mysite/workfront-reports/not-live-stale/csv/not-live-stale-natwest.csv";
        assertTrue(gen.written.containsKey(csvPath), gen.written.keySet().toString());
        // 3 data lines total (header + 2 matches) -> 2 rows.
        assertEquals(2, result.getTotalRows());
        final String csv = gen.written.get(csvPath);
        assertTrue(csv.contains("/content/natwest/a"), csv);
        assertTrue(csv.contains("/content/natwest/c"), csv);
        assertFalse(csv.contains("/content/natwest/b"), csv);
    }

    @Test
    void enforcesMaxRecordsCap() throws Exception {
        stalePage("/content/rbs/a", 8);
        stalePage("/content/rbs/b", 9);

        final ReportDefinition def = staleDefinition("rbs", "/content/rbs", 1);
        final CapturingGenerator gen = newGenerator(500);

        final ReportRunResult result = gen.generate(def);
        assertEquals(1, result.getTotalRows(), "capped at 1 record");
    }

    @Test
    void missingRootProducesHeaderOnlyCsv() throws Exception {
        final ReportDefinition def = staleDefinition("ulster", "/content/does-not-exist", 0);
        final CapturingGenerator gen = newGenerator(500);

        final ReportRunResult result = gen.generate(def);
        assertTrue(result.isSuccess());
        assertEquals(0, result.getTotalRows());
        final String csvPath = "/content/dam/mysite/workfront-reports/not-live-stale/csv/not-live-stale-ulster.csv";
        assertTrue(gen.written.get(csvPath).startsWith("Hash,Title,Path"));
    }

    @Test
    void daysToReviewColumnIsComputed() {
        final ReportDefinition def = allLiveDefinition();
        final Calendar review = Calendar.getInstance();
        review.add(Calendar.DAY_OF_MONTH, 30);
        final Resource p = page("/content/natwest/r",
                "jcr:primaryType", "cq:PageContent", "contentReviewExpiryDate", review);

        final StringBuilder csv = new StringBuilder();
        new ReportGeneratorServiceImpl().appendPageRowIfMatched(context.resourceResolver(), p, def, acceptAll, csv);
        final long expected = java.time.temporal.ChronoUnit.DAYS.between(
                java.time.LocalDate.now(), com.mysite.core.util.DateUtils.toLocalDate(review));
        assertTrue(csv.toString().contains("," + expected + ","), csv + " expected=" + expected);
    }

    @Test
    void brandFailureIsIsolatedAndRunStillSucceeds() throws Exception {
        stalePage("/content/natwest/a", 8);
        final ReportDefinition def = staleDefinition("natwest", "/content/natwest", 0);

        // A brand whose CSV write blows up must not fail the whole run.
        final ReportGeneratorServiceImpl gen = new ReportGeneratorServiceImpl() {
            @Override
            void writeAsset(final ResourceResolver resolver, final String csvPath, final String csvContent) {
                throw new IllegalStateException("write boom");
            }
            @Override
            void replicate(final ResourceResolver resolver, final String csvPath) {
                // no-op
            }
        };
        final ResourceResolverFactory rrf = mock(ResourceResolverFactory.class);
        when(rrf.getServiceResourceResolver(any())).thenReturn(context.resourceResolver());
        setField(gen, "resolverFactory", rrf);
        setField(gen, "replicator", mock(Replicator.class));
        setField(gen, "pageBatchSize", 500);

        final ReportRunResult result = gen.generate(def);
        assertTrue(result.isSuccess());
        assertEquals(0, result.getTotalRows());
    }

    @Test
    void loginFailureReturnsFailure() throws Exception {
        final CapturingGenerator gen = new CapturingGenerator();
        final ResourceResolverFactory rrf = mock(ResourceResolverFactory.class);
        when(rrf.getServiceResourceResolver(any())).thenThrow(new LoginException("no user"));
        setField(gen, "resolverFactory", rrf);

        final ReportDefinition def = staleDefinition("x", "/content/x", 0);
        final ReportRunResult result = gen.generate(def);
        assertFalse(result.isSuccess());
    }

    @Test
    void unexpectedErrorReturnsFailure() throws Exception {
        final CapturingGenerator gen = new CapturingGenerator();
        final ResourceResolverFactory rrf = mock(ResourceResolverFactory.class);
        when(rrf.getServiceResourceResolver(any())).thenThrow(new RuntimeException("boom"));
        setField(gen, "resolverFactory", rrf);

        assertFalse(gen.generate(staleDefinition("x", "/content/x", 0)).isSuccess());
    }

    @Test
    void traversesAcrossBatchBoundaries() throws Exception {
        stalePage("/content/nw/a", 8);
        stalePage("/content/nw/b", 9);
        final ReportDefinition def = staleDefinition("nw", "/content/nw", 0);

        // pageBatchSize=1 forces a session refresh + cool-down check on every page.
        final CapturingGenerator gen = newGenerator(1);

        assertEquals(2, gen.generate(def).getTotalRows());
    }

    @Test
    void activateReadsBatchSize() {
        final ReportGeneratorServiceImpl s = new ReportGeneratorServiceImpl();
        final ReportGeneratorServiceImpl.Config cfg = mock(ReportGeneratorServiceImpl.Config.class);
        when(cfg.pageBatchSize()).thenReturn(250);
        s.activate(cfg);
        // no exception; batch size applied internally
    }

    @Test
    void writesAndActivatesViaAssetManager() throws Exception {
        // Fully-mocked resolver so the real writeAsset + replicate seams run.
        final ResourceResolver resolver = mock(ResourceResolver.class);
        // The brand root resolves to an empty (non-page) node, so the walk yields 0 rows.
        final Resource rootRes = mock(Resource.class);
        when(rootRes.getChildren()).thenReturn(java.util.Collections.emptyList());
        when(rootRes.getValueMap()).thenReturn(org.apache.sling.api.resource.ValueMap.EMPTY);
        when(resolver.getResource("/content/x")).thenReturn(rootRes);
        final Session session = mock(Session.class);
        when(resolver.adaptTo(Session.class)).thenReturn(session);
        final AssetManager am = mock(AssetManager.class);
        when(resolver.adaptTo(AssetManager.class)).thenReturn(am);

        final ResourceResolverFactory rrf = mock(ResourceResolverFactory.class);
        when(rrf.getServiceResourceResolver(any())).thenReturn(resolver);

        final Replicator replicator = mock(Replicator.class);

        // Build an all-live definition with activateCsv=true and a single brand root.
        context.build().resource("/content/cfg/wr",
                "sling:resourceType", ReportType.ALL_LIVE.getResourceType(),
                "outputFolder", "/content/dam/mysite/reports/all-live",
                "activateCsv", Boolean.TRUE)
                .resource("/content/cfg/wr/brands/item0", "brand", "mysite", "rootPath", "/content/x")
                .commit();
        final ReportDefinition def = ReportDefinitionReader.readOne(
                context.resourceResolver().getResource("/content/cfg/wr"));

        final ReportGeneratorServiceImpl gen = new ReportGeneratorServiceImpl();
        setField(gen, "resolverFactory", rrf);
        setField(gen, "replicator", replicator);
        setField(gen, "pageBatchSize", 500);

        final ReportRunResult res = gen.generate(def);
        assertTrue(res.isSuccess(), res.getErrorMessage());

        final String csvPath = "/content/dam/mysite/reports/all-live/csv/all-live-mysite.csv";
        org.mockito.Mockito.verify(am).createAsset(org.mockito.ArgumentMatchers.eq(csvPath),
                any(), org.mockito.ArgumentMatchers.eq("text/csv"), org.mockito.ArgumentMatchers.eq(true));
        org.mockito.Mockito.verify(resolver).commit();
        org.mockito.Mockito.verify(replicator).replicate(session, ReplicationActionType.ACTIVATE, csvPath);
    }

    @Test
    void resolvesUrlViaExternalizerAndJoinsMultiValues() throws Exception {
        // Columns are the fixed schema now, so build a definition directly with the
        // two columns under test (:url token + a multi-value property).
        final ReportDefinition def = new ReportDefinition(
                "/content/cfg/cols", ReportType.ALL_LIVE,
                java.util.Collections.singletonList(new com.mysite.core.reports.BrandScope("all", "/content")),
                0, 0, "cq:lastModified", 0, 0, "cq:lastModified",
                java.util.Collections.emptyList(), null, 0, "/content/dam/x", false, false,
                java.util.Arrays.asList(
                        new com.mysite.core.models.report.ReportColumn("URL", ":url"),
                        new com.mysite.core.models.report.ReportColumn("Tags", "tags")));

        final Resource p = page("/content/natwest/p",
                "jcr:primaryType", "cq:PageContent", "tags", new String[]{"a", "b"});

        final Externalizer ext = mock(Externalizer.class);
        when(ext.publishLink(any(), org.mockito.ArgumentMatchers.eq("/content/natwest/p")))
                .thenReturn("http://host/content/natwest/p");

        final ReportGeneratorServiceImpl gen = new ReportGeneratorServiceImpl();
        setField(gen, "externalizer", ext);

        final StringBuilder csv = new StringBuilder();
        assertTrue(gen.appendPageRowIfMatched(context.resourceResolver(), p, def, acceptAll, csv));
        final String row = csv.toString();
        assertTrue(row.contains("http://host/content/natwest/p.html"), row);
        assertTrue(row.contains("a;b"), row);
    }

    // ---- helpers ----------------------------------------------------------------

    private static Object[] concat(final Object[] a, final Object[] b) {
        final Object[] out = new Object[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static void setField(final Object target, final String name, final Object value) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                final Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (final NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
