package io.github.vedmider.dylext.adapter.out.federation;

import com.sun.net.httpserver.HttpServer;
import io.github.vedmider.dylext.domain.port.out.FederatedRetriever.DocHit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Federation adapter contract: caller roles reach Sestacon's RBAC; any backend failure degrades to empty. */
class HttpFederatedRetrieverTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> seenRoles = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseBody = "[]";

    @BeforeEach
    void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/retrieve", exchange -> {
            seenRoles.set(exchange.getRequestHeaders().getFirst("X-Roles"));
            exchange.getRequestBody().readAllBytes();
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private HttpFederatedRetriever retriever() {
        return new HttpFederatedRetriever(baseUrl, "http://127.0.0.1:1");  // cm url unused here
    }

    @Test
    void callerRolesArePropagatedToSestaconAsXRolesHeader() {
        responseBody = "[{\"source\":\"doc.md\",\"score\":0.9,\"text\":\"body\"}]";

        List<DocHit> hits = retriever().explain("how does rbac work", "sre,admin", 3);

        assertThat(seenRoles.get()).isEqualTo("sre,admin");
        assertThat(hits).singleElement().extracting(DocHit::source).isEqualTo("doc.md");
    }

    @Test
    void noRolesSendsNoHeader() {
        retriever().explain("q", null, 3);
        assertThat(seenRoles.get()).isNull();
    }

    @Test
    void nonTwoHundredDegradesToEmpty() {
        status = 503;
        responseBody = "nope";
        assertThat(retriever().explain("q", "sre", 3)).isEmpty();
    }

    @Test
    void malformedBodyDegradesToEmpty() {
        status = 200;
        responseBody = "{not json";
        assertThat(retriever().explain("q", null, 3)).isEmpty();
    }

    @Test
    void unreachableServiceDegradesToEmpty() {
        var deadRetriever = new HttpFederatedRetriever("http://127.0.0.1:1", "http://127.0.0.1:1");
        assertThat(deadRetriever.explain("q", "sre", 3)).isEmpty();
    }
}
