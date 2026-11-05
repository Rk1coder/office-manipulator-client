package com.officemanipulator.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Without YAVER_API_KEYS this is a local tool: localhost only, same-origin API. With keys it is an API
 * service: any host may call /api/** with a valid key and listed origins get CORS. There is no web UI: only
 * the API, its docs and /health are served. Signed ONLYOFFICE transport routes and /internal stay as before.
 */
@Component
public class WebConfig extends OncePerRequestFilter {
    private static final Set<String> HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");
    @org.springframework.beans.factory.annotation.Value("${app.office.enabled:false}")
    private boolean officeEnabled;
    @org.springframework.beans.factory.annotation.Value("${app.office.app-internal-url:http://office-app:8080}")
    private String appInternalUrl="http://office-app:8080";
    @org.springframework.beans.factory.annotation.Value("${app.office.transport.port:0}")
    private int officeTransportPort;
    private List<byte[]> apiKeys=List.of();
    private Set<String> allowedOrigins=Set.of();
    private static final List<String> PUBLIC_PREFIXES=List.of("/api/", "/docs", "/swagger-ui", "/v3/api-docs", "/health");

    @org.springframework.beans.factory.annotation.Value("${app.api.keys:}")
    void setApiKeys(String raw) {
        apiKeys=split(raw).stream().peek(k -> {
            if(k.length()<24) throw new IllegalStateException("YAVER_API_KEYS: each key must be at least 24 characters (openssl rand -hex 32)");
        }).map(k -> k.getBytes(StandardCharsets.UTF_8)).toList();
    }
    @org.springframework.beans.factory.annotation.Value("${app.api.allowed-origins:}")
    void setAllowedOrigins(String raw) { allowedOrigins=Set.copyOf(split(raw)); }
    private static List<String> split(String raw) {
        return raw==null ? List.of() : Arrays.stream(raw.split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList();
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path=request.getRequestURI();
        // Container access is allowed only for routes that verify a document ticket or callback JWT.
        boolean conversionSource=path.matches("/api/office/convert-source/[0-9a-fA-F-]{36}");
        boolean officeTransport=officeEnabled && (path.matches("/api/office/sessions/[0-9a-fA-F-]{36}/(document|callback)")
            || path.matches("/api/office/preview/[0-9a-fA-F-]{36}/document") || conversionSource);
        boolean transportListener=officeTransportPort>0 && request.getLocalPort()==officeTransportPort;
        boolean transportMethod=((path.endsWith("/document") || conversionSource) && "GET".equals(request.getMethod()))
            || (path.endsWith("/callback") && "POST".equals(request.getMethod()));
        // Test the actual connector, not Host: a container must not spoof localhost to reach APIs.
        if(transportListener && (!officeTransport || !transportMethod)) {
            response.sendError(403,"This listener only accepts signed document transport");return;
        }
        boolean internalHost=officeTransport && URI.create(appInternalUrl).getHost().equalsIgnoreCase(request.getServerName());
        boolean apiMode=!apiKeys.isEmpty(), internal=path.startsWith("/internal/");
        boolean local=HOSTS.contains(request.getServerName().toLowerCase(Locale.ROOT)) || internalHost;
        if (!local && (!apiMode || internal)) {
            response.sendError(403, "Only localhost access is supported"); return;
        }
        if (!internal && PUBLIC_PREFIXES.stream().noneMatch(path::startsWith)) {
            if (path.equals("/")) response.sendRedirect("/docs"); else response.sendError(404);
            return;
        }
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        if (request.getRequestURI().startsWith("/api/")) {
            response.setHeader("Cache-Control", "no-store");
            String origin = request.getHeader("Origin");
            if (origin != null && allowedOrigins.contains(origin)) {
                response.setHeader("Access-Control-Allow-Origin", origin);
                response.setHeader("Vary", "Origin");
                response.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, X-API-Key");
                response.setHeader("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS");
                response.setHeader("Access-Control-Expose-Headers", "Content-Disposition");
                if ("OPTIONS".equals(request.getMethod())) { response.setStatus(204); return; }
            } else if (!sameOrigin(request, origin)) { response.sendError(403, "Cross-origin API access is disabled"); return; }
            // Signed ONLYOFFICE transport carries its own ticket/JWT and never an API key.
            if (apiMode && !officeTransport && !hasValidKey(request)) {
                response.setStatus(401);
                response.setHeader("WWW-Authenticate", "Bearer realm=\"yaver\"");
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"Geçerli bir API anahtarı gerekli: Authorization: Bearer <anahtar> veya X-API-Key başlığı.\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean sameOrigin(HttpServletRequest request, String origin) {
        boolean allowed = !"cross-site".equals(request.getHeader("Sec-Fetch-Site"));
        if (origin != null) {
            try {
                URI uri = URI.create(origin);
                int port = uri.getPort() == -1 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
                allowed &= uri.getHost() != null && uri.getHost().equalsIgnoreCase(request.getServerName())
                    && uri.getScheme().equals(request.getScheme()) && port == request.getServerPort()
                    && uri.getRawUserInfo() == null && (uri.getPath() == null || uri.getPath().isEmpty());
            } catch (RuntimeException e) { allowed = false; }
        }
        return allowed;
    }

    private boolean hasValidKey(HttpServletRequest request) {
        String auth=request.getHeader("Authorization"), key=request.getHeader("X-API-Key");
        if (auth!=null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) key=auth.substring(7).trim();
        if (key==null || key.isEmpty()) return false;
        byte[] given=key.getBytes(StandardCharsets.UTF_8);
        boolean ok=false;
        for (byte[] k : apiKeys) ok |= MessageDigest.isEqual(given, k); // constant time, no early exit
        return ok;
    }
}
