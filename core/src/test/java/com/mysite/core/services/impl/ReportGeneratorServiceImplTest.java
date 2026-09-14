package com.mysite.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
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
import com.day.cq.search.PredicateGroup;
import com.day.cq.search.Query;
import com.day.cq.search.QueryBuilder;
import com.day.cq.search.result.Hit;
import com.day.cq.search.result.SearchResult;
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
    void skipsPageWithExclusionFlag() {
        final ReportDefinition def = allLiveDefinition();
        final Resource page = page("/content/natwest/x",
                "jcr:primaryType", "cq:PageContent", "excludeFromReport", "true");
        assertFalse(new ReportGeneratorServiceImpl().appendPageRowIfMatched(
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
                .resource(base + "/brands/item0", "brand", brand, "rootPaths", new String[]{root})
                .commit();
        return ReportDefinitionReader.readOne(context.resourceResolver().getResource(base));
    }

    private Resource stalePage(final String path, final int monthsAgo) {
        final Calendar cal = Calendar.getInstance();
        cal.add(Calendar.MONTH, -monthsAgo);
        return page(path, "jcr:primaryType", "cq:PageContent", "cq:lastModified", cal, "jcr:title", path);
    }

    private CapturingGenerator generatorReturningHits(final List<Resource> hits) throws Exception {
        final CapturingGenerator gen = new CapturingGenerator();

        final ResourceResolverFactory rrf = mock(ResourceResolverFactory.class);
        when(rrf.getServiceResourceResolver(any())).thenReturn(context.resourceResolver());

        final QueryBuilder qb = mock(QueryBuilder.class);
        final Query query = mock(Query.class);
        final SearchResult result = mock(SearchResult.class);
        final List<Hit> hitList = new ArrayList<>();
        for (final Resource r : hits) {
            final Hit hit = mock(Hit.class);
            when(hit.getResource()).thenReturn(r);
            hitList.add(hit);
        }
        when(qb.createQuery(any(PredicateGroup.class), any())).thenReturn(query);
        when(query.getResult()).thenReturn(result);
        when(result.getHits()).thenReturn(hitList);

        setField(gen, "resolverFactory", rrf);
        setField(gen, "queryBuilder", qb);
        setField(gen, "replicator", mock(Replicator.class));
        setField(gen, "pageBatchSize", 500);
        return gen;
    }

    @Test
    void generatesPerBrandCsvForMatchingPages() throws Exception {
        final Resource oldA = stalePage("/content/natwest/a", 8);
        final Resource fresh = stalePage("/content/natwest/b", 1);
        final Resource oldC = stalePage("/content/natwest/c", 9);

        final ReportDefinition def = staleDefinition("natwest", "/content/natwest", 0);
        final CapturingGenerator gen = generatorReturningHits(java.util.Arrays.asList(oldA, fresh, oldC));

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
        final Resource oldA = stalePage("/content/rbs/a", 8);
        final Resource oldB = stalePage("/content/rbs/b", 9);

        final ReportDefinition def = staleDefinition("rbs", "/content/rbs", 1);
        final CapturingGenerator gen = generatorReturningHits(java.util.Arrays.asList(oldA, oldB));

        final ReportRunResult result = gen.generate(def);
        assertEquals(1, result.getTotalRows(), "capped at 1 record");
    }

    @Test
    void missingRootProducesHeaderOnlyCsv() throws Exception {
        final ReportDefinition def = staleDefinition("ulster", "/content/does-not-exist", 0);
        final CapturingGenerator gen = generatorReturningHits(java.util.Collections.emptyList());

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
        page("/content/natwest/a", "jcr:primaryType", "cq:PageContent");
        final ReportDefinition def = staleDefinition("natwest", "/content/natwest", 0);

        final CapturingGenerator gen = new CapturingGenerator();
        final ResourceResolverFactory rrf = mock(ResourceResolverFactory.class);
        when(rrf.getServiceResourceResolver(any())).thenReturn(context.resourceResolver());
        final QueryBuilder qb = mock(QueryBuilder.class);
        when(qb.createQuery(any(PredicateGroup.class), any())).thenThrow(new RuntimeException("query boom"));
        setField(gen, "resolverFactory", rrf);
        setField(gen, "queryBuilder", qb);
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
    void paginatesAcrossBatches() throws Exception {
        final Resource p1 = stalePage("/content/nw/a", 8);
        final Resource p2 = stalePage("/content/nw/b", 9);
        final ReportDefinition def = staleDefinition("nw", "/content/nw", 0);

        final CapturingGenerator gen = new CapturingGenerator();
        final ResourceResolverFactory rrf = mock(ResourceResolverFactory.class);
        when(rrf.getServiceResourceResolver(any())).thenReturn(context.resourceResolver());
        final QueryBuilder qb = mock(QueryBuilder.class);
        final Query query = mock(Query.class);
        final SearchResult result = mock(SearchResult.class);
        final Hit h1 = mock(Hit.class);
        when(h1.getResource()).thenReturn(p1);
        final Hit h2 = mock(Hit.class);
        when(h2.getResource()).thenReturn(p2);
        when(qb.createQuery(any(PredicateGroup.class), any())).thenReturn(query);
        when(query.getResult()).thenReturn(result);
        when(result.getHits()).thenReturn(
                java.util.Collections.singletonList(h1),
                java.util.Collections.singletonList(h2),
                java.util.Collections.emptyList());
        setField(gen, "resolverFactory", rrf);
        setField(gen, "queryBuilder", qb);
        setField(gen, "replicator", mock(Replicator.class));
        setField(gen, "pageBatchSize", 1); // force pagination across batches

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
        when(resolver.getResource("/content/x")).thenReturn(mock(Resource.class));
        final Session session = mock(Session.class);
        when(resolver.adaptTo(Session.class)).thenReturn(session);
        final AssetManager am = mock(AssetManager.class);
        when(resolver.adaptTo(AssetManager.class)).thenReturn(am);

        final ResourceResolverFactory rrf = mock(ResourceResolverFactory.class);
        when(rrf.getServiceResourceResolver(any())).thenReturn(resolver);

        final QueryBuilder qb = mock(QueryBuilder.class);
        final Query query = mock(Query.class);
        final SearchResult result = mock(SearchResult.class);
        when(qb.createQuery(any(PredicateGroup.class), any())).thenReturn(query);
        when(query.getResult()).thenReturn(result);
        when(result.getHits()).thenReturn(java.util.Collections.emptyList());

        final Replicator replicator = mock(Replicator.class);

        // Build an all-live definition with activateCsv=true and a single brand root.
        context.build().resource("/content/cfg/wr",
                "sling:resourceType", ReportType.ALL_LIVE.getResourceType(),
                "outputFolder", "/content/dam/mysite/reports/all-live",
                "activateCsv", Boolean.TRUE)
                .resource("/content/cfg/wr/brands/item0", "brand", "mysite", "rootPaths", new String[]{"/content/x"})
                .commit();
        final ReportDefinition def = ReportDefinitionReader.readOne(
                context.resourceResolver().getResource("/content/cfg/wr"));

        final ReportGeneratorServiceImpl gen = new ReportGeneratorServiceImpl();
        setField(gen, "resolverFactory", rrf);
        setField(gen, "queryBuilder", qb);
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
        context.build().resource("/content/cfg/cols",
                "sling:resourceType", ReportType.ALL_LIVE.getResourceType())
                .resource("/content/cfg/cols/columns/item0", "header", "URL", "source", ":url")
                .resource("/content/cfg/cols/columns/item1", "header", "Tags", "source", "tags")
                .commit();
        final ReportDefinition def = ReportDefinitionReader.readOne(
                context.resourceResolver().getResource("/content/cfg/cols"));

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
