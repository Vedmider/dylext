package io.github.vedmider.dylext.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vedmider.dylext.domain.model.VerifiedAnswer.Citation;
import io.github.vedmider.dylext.domain.model.VerifiedAnswer.Status;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the wire shape an MCP/agent client depends on; a renamed field breaks clients silently. */
class VerifiedAnswerJsonTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void serializesStableKeysAndStatusAsString() throws Exception {
        VerifiedAnswer va = new VerifiedAnswer("the answer",
                List.of(Citation.verified("greet", "r:G.java:G#greet", "String greet()", "G.java", 42),
                        Citation.unverified("ghost")),
                1);

        JsonNode tree = json.readTree(json.writeValueAsString(va));

        assertThat(tree.has("answer")).isTrue();
        assertThat(tree.has("citations")).isTrue();
        assertThat(tree.has("unverifiedCount")).isTrue();
        assertThat(tree.path("unverifiedCount").asInt()).isEqualTo(1);

        JsonNode c0 = tree.path("citations").get(0);
        assertThat(c0.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("token", "status", "symbolId", "signature",
                        "filePath", "startLine");
        assertThat(c0.path("status").asText()).isEqualTo("VERIFIED");
        assertThat(c0.path("signature").asText()).isEqualTo("String greet()");
        assertThat(c0.path("startLine").asInt()).isEqualTo(42);

        assertThat(tree.path("citations").get(1).path("status").asText()).isEqualTo("UNVERIFIED");
    }

    @Test
    void roundTripsBackToEqualValue() throws Exception {
        VerifiedAnswer va = new VerifiedAnswer("a",
                List.of(Citation.unverified("X")), 1);
        VerifiedAnswer back = json.readValue(json.writeValueAsString(va), VerifiedAnswer.class);
        assertThat(back).isEqualTo(va);
        assertThat(back.citations().get(0).status()).isEqualTo(Status.UNVERIFIED);
    }
}
