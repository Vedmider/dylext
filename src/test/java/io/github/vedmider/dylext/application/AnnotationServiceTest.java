package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.domain.model.Summary;
import io.github.vedmider.dylext.domain.model.Symbol;
import io.github.vedmider.dylext.domain.model.SymbolKind;
import io.github.vedmider.dylext.domain.port.out.DigestStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnnotationServiceTest {

    private final DigestStore store = mock(DigestStore.class);
    private final FreshnessGate freshness = mock(FreshnessGate.class);
    private final AnnotationService service = new AnnotationService(store, freshness);

    private static Symbol symbol(String id, String hash) {
        return new Symbol(id, "r", "A.java", "java", SymbolKind.METHOD, "m", "A#m",
                "void m()", "", 1, 2, List.of(), hash);
    }

    @Test
    void storesAgentNoteStampedWithTheSymbolsCurrentHash() {
        when(store.findSymbol("r:A.java:A#m")).thenReturn(Optional.of(symbol("r:A.java:A#m", "h1")));

        var result = service.annotate("r:A.java:A#m", "  orchestrates routing  ");

        assertThat(result.stored()).isTrue();
        assertThat(result.contentHash()).isEqualTo("h1");
        ArgumentCaptor<Summary> saved = ArgumentCaptor.forClass(Summary.class);
        verify(store).saveSummary(saved.capture());
        assertThat(saved.getValue().text()).isEqualTo("orchestrates routing");
        assertThat(saved.getValue().contentHash()).isEqualTo("h1");
        verify(freshness).ensureFresh("r");
    }

    @Test
    void blankTextIsRejected() {
        assertThatThrownBy(() -> service.annotate("r:A.java:A#m", "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownSymbolIsRejected() {
        when(store.findSymbol("r:A.java:ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.annotate("r:A.java:ghost", "note"))
                .isInstanceOf(NoSuchElementException.class);
    }
}
