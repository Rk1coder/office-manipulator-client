package com.officemanipulator.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ApiServiceModeTest {
    private static final String KEY = "k".repeat(32), OTHER = "o".repeat(32);

    private WebConfig filter(String keys) {
        WebConfig filter = new WebConfig();
        ReflectionTestUtils.setField(filter, "officeEnabled", true);
        ReflectionTestUtils.setField(filter, "appInternalUrl", "http://app:8080");
        filter.setApiKeys(keys);
        filter.setAllowedOrigins("https://portal.example.com");
        return filter;
    }

    private static MockHttpServletRequest get(String path, String host) {
        var request = new MockHttpServletRequest("GET", path);
        request.setServerName(host);
        return request;
    }

    /** Runs the filter and reports the status, or 0 when the request reached the controller. */
    private static int run(WebConfig filter, MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        var reached = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> reached.set(true));
        return reached.get() ? 0 : response.getStatus();
    }

    @Test void remoteCallersNeedAValidKeyFromAnyHost() throws Exception {
        var f = filter(KEY + "," + OTHER);
        assertEquals(401, run(f, get("/api/jobs", "api.example.com")), "no key");
        var wrong = get("/api/jobs", "api.example.com"); wrong.addHeader("Authorization", "Bearer " + "x".repeat(32));
        assertEquals(401, run(f, wrong), "wrong key");
        var bearer = get("/api/jobs", "api.example.com"); bearer.addHeader("Authorization", "bearer " + KEY);
        assertEquals(0, run(f, bearer), "Bearer key, any host");
        var header = get("/api/jobs", "10.0.0.5"); header.addHeader("X-API-Key", OTHER);
        assertEquals(0, run(f, header), "second key via X-API-Key");
    }

    @Test void unauthorizedResponseSaysHowToAuthenticate() throws Exception {
        var response = new MockHttpServletResponse();
        filter(KEY).doFilter(get("/api/budget", "localhost"), response, (req, res) -> fail("must not reach controller"));
        assertEquals(401, response.getStatus());
        assertEquals("Bearer realm=\"yaver\"", response.getHeader("WWW-Authenticate"));
        assertTrue(response.getContentAsString().contains("Authorization: Bearer"));
    }

    @Test void healthDocsAndSignedOfficeTransportNeedNoKey() throws Exception {
        var f = filter(KEY);
        assertEquals(0, run(f, get("/health", "api.example.com")));
        assertEquals(0, run(f, get("/v3/api-docs", "api.example.com")));
        assertEquals(0, run(f, get("/docs", "api.example.com")));
        assertEquals(0, run(f, get("/api/office/preview/" + java.util.UUID.randomUUID() + "/document", "app")), "ticket-checked transport");
    }

    @Test void noWebUiAndInternalRoutesStayLocal() throws Exception {
        var f = filter(KEY);
        assertEquals(404, run(f, get("/index.html", "localhost")));
        assertEquals(404, run(f, get("/office-plugin/config.json", "localhost")));
        assertEquals(404, run(filter(""), get("/index.html", "localhost")), "no UI even as a local tool");
        var root = new MockHttpServletResponse();
        f.doFilter(get("/", "api.example.com"), root, (req, res) -> fail("root must redirect"));
        assertEquals("/docs", root.getRedirectedUrl());
        assertEquals(403, run(f, get("/internal/agent-tools/create-word", "api.example.com")), "internal stays local-only");
    }

    @Test void listedOriginsGetCorsOthersAreRejected() throws Exception {
        var f = filter(KEY);
        var preflight = new MockHttpServletRequest("OPTIONS", "/api/chat");
        preflight.setServerName("api.example.com"); preflight.addHeader("Origin", "https://portal.example.com");
        var response = new MockHttpServletResponse();
        f.doFilter(preflight, response, (req, res) -> fail("preflight is answered by the filter"));
        assertEquals(204, response.getStatus());
        assertEquals("https://portal.example.com", response.getHeader("Access-Control-Allow-Origin"));
        var evil = get("/api/jobs", "api.example.com"); evil.addHeader("Origin", "https://evil.example.com"); evil.addHeader("Authorization", "Bearer " + KEY);
        assertEquals(403, run(f, evil));
    }

    @Test void withoutKeysItStaysALocalOnlyTool() throws Exception {
        var f = filter("");
        assertEquals(0, run(f, get("/api/jobs", "localhost")), "local, no key needed");
        assertEquals(403, run(f, get("/api/jobs", "api.example.com")), "remote refused");
    }

    @Test void shortKeysAreRejectedAtStartup() {
        assertThrows(IllegalStateException.class, () -> new WebConfig().setApiKeys("too-short"));
    }
}
