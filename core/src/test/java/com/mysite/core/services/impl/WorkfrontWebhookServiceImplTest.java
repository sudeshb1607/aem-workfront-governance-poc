package com.mysite.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.mysite.core.services.WorkfrontWebhookService.SendResult;
import com.mysite.core.util.HmacUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.wcm.testing.mock.aem.junit5.AemContext;

/**
 * Exercises the signing + HTTP send path of {@link WorkfrontWebhookServiceImpl}
 * against a real in-process HTTP server (JDK, no external deps, no DAM I/O), plus
 * the guard that refuses to send when the URL/secret is not configured.
 */
class WorkfrontWebhookServiceImplTest {

    private static final String SECRET = "unit-test-secret";

    private final AemContext context = new AemContext();

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void signsBodyAndPostsWithHeaders() throws Exception {
        final AtomicReference<String> seenMethod = new AtomicReference<>();
        final AtomicReference<String> seenSignature = new AtomicReference<>();
        final AtomicReference<String> seenDataset = new AtomicReference<>();
        final AtomicReference<byte[]> seenBody = new AtomicReference<>();

        final String url = startServer(exchange -> {
            seenMethod.set(exchange.getRequestMethod());
            seenSignature.set(exchange.getRequestHeaders().getFirst("X-Workfront-Signature"));
            seenDataset.set(exchange.getRequestHeaders().getFirst("X-Workfront-Dataset"));
            seenBody.set(readAll(exchange.getRequestBody()));
            final byte[] resp = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });

        final WorkfrontWebhookServiceImpl service = service(url, SECRET);
        final byte[] body = "{\"dataset\":\"us-en\",\"records\":[]}".getBytes(StandardCharsets.UTF_8);

        final SendResult result = service.sendBody("us-en", body);

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals(200, result.getHttpStatus());
        assertEquals("POST", seenMethod.get());
        assertEquals("us-en", seenDataset.get());
        assertArrayEquals(body, seenBody.get(), "the exact body must be POSTed");
        assertEquals("sha256=" + HmacUtil.sha256Hex(SECRET, body), seenSignature.get(),
                "signature must be HMAC-SHA256 of the exact body");
    }

    @Test
    void nonSuccessStatusIsFailure() throws Exception {
        final String url = startServer(exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        final WorkfrontWebhookServiceImpl service = service(url, SECRET);

        final SendResult result = service.sendBody("ds", "{}".getBytes(StandardCharsets.UTF_8));
        assertFalse(result.isSuccess());
        assertEquals(500, result.getHttpStatus());
    }

    @Test
    void refusesToSendWhenSecretMissing() {
        final WorkfrontWebhookServiceImpl service = service("http://localhost:1/hook", "");
        final SendResult result = service.sendBody("ds", "{}".getBytes(StandardCharsets.UTF_8));
        assertFalse(result.isSuccess());
        assertEquals(0, result.getHttpStatus());
    }

    @Test
    void refusesToSendWhenUrlMissing() {
        final WorkfrontWebhookServiceImpl service = service("", SECRET);
        final SendResult result = service.sendBody("ds", "{}".getBytes(StandardCharsets.UTF_8));
        assertFalse(result.isSuccess());
    }

    private WorkfrontWebhookServiceImpl service(final String url, final String secret) {
        final Map<String, Object> config = new HashMap<>();
        config.put("webhookUrl", url);
        config.put("webhookSecret", secret);
        return context.registerInjectActivateService(new WorkfrontWebhookServiceImpl(), config);
    }

    private String startServer(final Handler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            try {
                handler.handle(exchange);
            } catch (final IOException e) {
                throw e;
            } catch (final Exception e) {
                throw new IOException(e);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    private static byte[] readAll(final InputStream in) {
        try (InputStream stream = in) {
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            final byte[] buffer = new byte[4096];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                bos.write(buffer, 0, read);
            }
            return bos.toByteArray();
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }
}
