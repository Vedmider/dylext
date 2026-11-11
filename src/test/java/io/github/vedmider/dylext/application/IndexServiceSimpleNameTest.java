package io.github.vedmider.dylext.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Qualified call targets (Go/Python) normalize to the simple name so edges resolve like Java's. */
class IndexServiceSimpleNameTest {

    @Test
    void qualifiedGoAndPythonTargetsNormalizeToSimpleName() {
        assertThat(IndexService.simpleName("str.ToUpper")).isEqualTo("ToUpper");
        assertThat(IndexService.simpleName("pkg.Func")).isEqualTo("Func");
        assertThat(IndexService.simpleName("self.prefix")).isEqualTo("prefix");
        assertThat(IndexService.simpleName("a.b.c.deep")).isEqualTo("deep");
    }

    @Test
    void bareJavaTargetsPassThroughUnchanged() {
        assertThat(IndexService.simpleName("searchSymbols")).isEqualTo("searchSymbols");
    }

    @Test
    void trailingDotIsNotStripped() {
        assertThat(IndexService.simpleName("weird.")).isEqualTo("weird.");
    }
}
