package io.github.vedmider.dylext.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SymbolTest {

    private static Symbol symbol(String id, String repo, String file, String path, String hash) {
        return new Symbol(id, repo, file, "java", SymbolKind.METHOD, "m", path,
                "void m()", "", 1, 3, List.of(), hash);
    }

    @Test
    void idMustEqualRepoFilePathSymbolPath() {
        String repo = "r";
        String file = "A.java";
        String path = "A.m";
        Symbol s = symbol(Symbol.idOf(repo, file, path), repo, file, path, "h1");
        assertThat(s.id()).isEqualTo("r:A.java:A.m");
    }

    @Test
    void mismatchedIdIsRejected() {
        assertThatThrownBy(() -> symbol("wrong", "r", "A.java", "A.m", "h1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sameContentHashMeansInterchangeableForCaching() {
        String id = Symbol.idOf("r", "A.java", "A.m");
        assertThat(symbol(id, "r", "A.java", "A.m", "h1").contentHash())
                .isEqualTo(symbol(id, "r", "A.java", "A.m", "h1").contentHash());
    }

    @Test
    void callTargetsDefaultToEmptyAndAreImmutable() {
        Symbol s = new Symbol(Symbol.idOf("r", "A.java", "A.m"), "r", "A.java", "java",
                SymbolKind.METHOD, "m", "A.m", "void m()", "", 1, 3, null, "h");
        assertThat(s.callTargets()).isEmpty();
        assertThatThrownBy(() -> s.callTargets().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
