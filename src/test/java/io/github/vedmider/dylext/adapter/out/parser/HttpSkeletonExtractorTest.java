package io.github.vedmider.dylext.adapter.out.parser;

import com.sun.net.httpserver.HttpServer;
import io.github.vedmider.dylext.domain.model.Symbol;
import io.github.vedmider.dylext.domain.model.SymbolKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** HTTP parser adapter: maps neutral sidecar JSON to Symbols with Dylext's id scheme; degrades safely. */
class HttpSkeletonExtractorTest {

    private HttpServer server;
    private String baseUrl;
    private volatile int status = 200;
    private volatile String responseBody = "{\"symbols\":[]}";
    private volatile String seenRequest;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/parse", exchange -> {
            seenRequest = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpSkeletonExtractor extractor() {
        return new HttpSkeletonExtractor("python", baseUrl);
    }

    @Test
    void mapsSidecarSymbolsAndOwnsTheIdScheme() {
        responseBody = """
                {"symbols":[
                  {"kind":"CLASS","name":"Greeter","symbolPath":"Greeter","signature":"class Greeter",
                   "docFirstLine":"greets","startLine":1,"endLine":9},
                  {"kind":"METHOD","name":"hello","symbolPath":"Greeter#hello",
                   "signature":"def hello(self, name)","startLine":2,"endLine":4,"callTargets":["print"]}
                ]}""";

        List<Symbol> symbols = extractor().extract("vde", "pkg/greeter.py", "class Greeter:\n  pass\n");

        // a MODULE is always present (synthesized when the sidecar omits one)
        assertThat(symbols).anyMatch(s -> s.kind() == SymbolKind.MODULE);
        Symbol method = symbols.stream().filter(s -> s.name().equals("hello")).findFirst().orElseThrow();
        assertThat(method.id()).isEqualTo("vde:pkg/greeter.py:Greeter#hello");
        assertThat(method.language()).isEqualTo("python");
        assertThat(method.signature()).isEqualTo("def hello(self, name)");
        assertThat(method.callTargets()).containsExactly("print");
        assertThat(method.startLine()).isEqualTo(2);
    }

    @Test
    void sendsNeutralRequestPayload() {
        extractor().extract("vde", "a/b.py", "x = 1\n");
        assertThat(seenRequest).contains("\"language\":\"python\"", "\"filePath\":\"a/b.py\"",
                "\"repo\":\"vde\"", "\"source\"");
    }

    @Test
    void honoursAModuleSymbolFromTheSidecar() {
        responseBody = """
                {"symbols":[{"kind":"MODULE","name":"b.py","signature":"# module b"}]}""";
        List<Symbol> symbols = extractor().extract("vde", "a/b.py", "x=1\n");
        assertThat(symbols).singleElement().satisfies(s -> {
            assertThat(s.kind()).isEqualTo(SymbolKind.MODULE);
            assertThat(s.id()).isEqualTo("vde:a/b.py:<module>");
        });
    }

    @Test
    void nonTwoHundredDegradesToSingleModule() {
        status = 500;
        responseBody = "boom";
        List<Symbol> symbols = extractor().extract("vde", "a/b.py", "x=1\n");
        assertThat(symbols).singleElement().extracting(Symbol::kind).isEqualTo(SymbolKind.MODULE);
    }

    @Test
    void unreachableSidecarDegradesToSingleModule() {
        var dead = new HttpSkeletonExtractor("python", "http://127.0.0.1:1");
        List<Symbol> symbols = dead.extract("vde", "a/b.py", "x=1\n");
        assertThat(symbols).singleElement().extracting(Symbol::kind).isEqualTo(SymbolKind.MODULE);
    }

    @Test
    void malformedBodyDegradesToSingleModule() {
        responseBody = "{not json";
        List<Symbol> symbols = extractor().extract("vde", "a/b.py", "x=1\n");
        assertThat(symbols).singleElement().extracting(Symbol::kind).isEqualTo(SymbolKind.MODULE);
    }
}
