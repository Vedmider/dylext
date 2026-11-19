package io.github.vedmider.dylext.adapter.in.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpHttpAccessFilterTest {

    private HttpServletRequest req(String addr, String token) {
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getRemoteAddr()).thenReturn(addr);
        when(r.getRequestURI()).thenReturn("/mcp");
        when(r.getHeader("X-Dylext-Token")).thenReturn(token);
        return r;
    }

    private HttpServletResponse resp() throws Exception {
        HttpServletResponse r = mock(HttpServletResponse.class);
        when(r.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        return r;
    }

    @Test
    void onlyFiltersTheMcpPath() {
        McpHttpAccessFilter f = new McpHttpAccessFilter("");
        HttpServletRequest other = mock(HttpServletRequest.class);
        when(other.getRequestURI()).thenReturn("/actuator/health");
        assertThat(f.shouldNotFilter(other)).isTrue();
        HttpServletRequest mcp = mock(HttpServletRequest.class);
        when(mcp.getRequestURI()).thenReturn("/mcp");
        assertThat(f.shouldNotFilter(mcp)).isFalse();
    }

    @Test
    void loopbackIsAllowedWithoutAToken() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        HttpServletResponse resp = resp();
        new McpHttpAccessFilter("").doFilterInternal(req("127.0.0.1", null), resp, chain);
        verify(chain).doFilter(any(), any());
        verify(resp, never()).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void remoteWithoutATokenIsRejected() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        HttpServletResponse resp = resp();
        new McpHttpAccessFilter("").doFilterInternal(req("10.1.2.3", null), resp, chain);
        verify(chain, never()).doFilter(any(), any());
        verify(resp).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void remoteWithTheCorrectTokenIsAllowed() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        new McpHttpAccessFilter("s3cret").doFilterInternal(req("10.1.2.3", "s3cret"), resp(), chain);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void remoteWithAWrongTokenIsRejected() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        HttpServletResponse resp = resp();
        new McpHttpAccessFilter("s3cret").doFilterInternal(req("10.1.2.3", "nope"), resp, chain);
        verify(chain, never()).doFilter(any(), any());
        verify(resp).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }
}
