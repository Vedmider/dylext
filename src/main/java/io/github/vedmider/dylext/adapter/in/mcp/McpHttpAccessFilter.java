package io.github.vedmider.dylext.adapter.in.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Access guard for the HTTP dispatcher {@code POST /mcp}. Loopback callers are always allowed (keeps the
 * local {@code dx} CLI and co-located clients working); a remote caller is allowed only when a token is
 * configured via {@code dylext.mcp.http.token} and presented in {@code X-Dylext-Token}. With no token set,
 * {@code /mcp} is localhost-only. The endpoint runs the full tool dispatcher, so it must not be openly remote.
 */
@Component
public class McpHttpAccessFilter extends OncePerRequestFilter {

    private final String token;

    public McpHttpAccessFilter(@Value("${dylext.mcp.http.token:}") String token) {
        this.token = token == null ? "" : token.trim();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/mcp".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isLoopback(request.getRemoteAddr()) || tokenMatches(request)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"error\":\"unauthorized: /mcp requires a loopback caller or a valid X-Dylext-Token\"}");
    }

    private boolean tokenMatches(HttpServletRequest request) {
        if (token.isEmpty()) {
            return false;
        }
        String provided = request.getHeader("X-Dylext-Token");
        return provided != null
                && MessageDigest.isEqual(provided.trim().getBytes(StandardCharsets.UTF_8),
                        token.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isLoopback(String addr) {
        return addr != null
                && (addr.startsWith("127.") || addr.equals("::1") || addr.equals("0:0:0:0:0:0:0:1"));
    }
}
