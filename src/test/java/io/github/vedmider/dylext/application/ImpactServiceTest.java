package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.config.LanguageProperties;
import io.github.vedmider.dylext.domain.model.CapabilityTier;
import io.github.vedmider.dylext.domain.model.Symbol;
import io.github.vedmider.dylext.domain.model.SymbolKind;
import io.github.vedmider.dylext.domain.port.in.ImpactUseCase.Impact;
import io.github.vedmider.dylext.domain.port.in.LedgerUseCase;
import io.github.vedmider.dylext.domain.port.out.DigestStore;
import io.github.vedmider.dylext.domain.port.out.SkeletonExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImpactServiceTest {

    private final DigestStore store = mock(DigestStore.class);
    private final LedgerUseCase ledger = mock(LedgerUseCase.class);
    private final io.github.vedmider.dylext.domain.port.out.SignalStore signals =
            mock(io.github.vedmider.dylext.domain.port.out.SignalStore.class);
    private final FreshnessGate freshness = mock(FreshnessGate.class);

    private static SkeletonExtractor extractor(String language, CapabilityTier tier) {
        return new SkeletonExtractor() {
            public List<Symbol> extract(String repo, String filePath, String source) {
                return List.of();
            }
            public String language() {
                return language;
            }
            public CapabilityTier tier() {
                return tier;
            }
        };
    }

    private ImpactService service() {
        LanguageRouter router = new LanguageRouter(
                List.of(extractor("ruby", CapabilityTier.T1_STRUCTURE),
                        extractor("go", CapabilityTier.T2_EDGES)),
                new LanguageProperties());
        return new ImpactService(store, ledger, router, signals, freshness);
    }

    private Symbol symbol(String repo, String file, String path, String language) {
        return new Symbol(Symbol.idOf(repo, file, path), repo, file, language, SymbolKind.METHOD, "m", path,
                "sig", "", 1, 2, List.of(), "h");
    }

    @Test
    void impactBelowT2ReturnsACapabilityNoteInsteadOfAMisleadingEmptyResult() {
        String id = Symbol.idOf("r", "f.rb", "C#m");
        when(store.findSymbol(id)).thenReturn(Optional.of(symbol("r", "f.rb", "C#m", "ruby")));
        Impact impact = service().impactOf(id);
        assertThat(impact.capabilityNote()).contains("T2").contains("ruby").contains("T1");
        assertThat(impact.callers()).isEmpty();
        verify(store, never()).callersOf(id);
    }

    @Test
    void impactAtT2ProceedsNormallyWithNoNote() {
        String id = Symbol.idOf("r", "f.go", "C#m");
        when(store.findSymbol(id)).thenReturn(Optional.of(symbol("r", "f.go", "C#m", "go")));
        when(store.callersOf(id)).thenReturn(List.of("r:other.go:D#n"));
        when(store.testsFor(id)).thenReturn(List.of());
        Impact impact = service().impactOf(id);
        assertThat(impact.capabilityNote()).isNull();
        assertThat(impact.callers()).containsExactly("r:other.go:D#n");
    }

    @Test
    void capsCallersToTheLimitAndFlagsTruncationForBigBlastRadii() {
        String id = Symbol.idOf("r", "f.go", "C#m");
        when(store.findSymbol(id)).thenReturn(Optional.of(symbol("r", "f.go", "C#m", "go")));
        when(store.callersOf(id)).thenReturn(List.of("r:a.go:A#x", "r:b.go:B#y", "r:c.go:C#z"));
        when(store.testsFor(id)).thenReturn(List.of());

        Impact capped = service().impactOf(id, 2);

        assertThat(capped.callers()).hasSize(2);
        assertThat(capped.truncated()).isTrue();
        assertThat(capped.totalCallers()).isEqualTo(3);

        Impact uncapped = service().impactOf(id, 0);
        assertThat(uncapped.callers()).hasSize(3);
        assertThat(uncapped.truncated()).isFalse();
    }

    @Test
    void runtimeSignalsReorderCallersHotAndUntestedFirstAndExposeWeight() {
        String id = Symbol.idOf("r", "f.go", "C#m");
        String low = "r:a.go:A#x";
        String high = "r:b.go:B#y";
        when(store.findSymbol(id)).thenReturn(Optional.of(symbol("r", "f.go", "C#m", "go")));
        when(store.callersOf(id)).thenReturn(List.of(low, high));   // insertion order: low first
        when(store.testsFor(id)).thenReturn(List.of());
        when(signals.forSymbols(List.of(low, high))).thenReturn(java.util.Map.of(
                low, io.github.vedmider.dylext.domain.model.SymbolSignal.of(low, 0.9, 10),    // well covered -> low weight
                high, io.github.vedmider.dylext.domain.model.SymbolSignal.of(high, 0.0, 10))); // untested+hot -> high weight

        Impact impact = service().impactOf(id);

        assertThat(impact.callers()).containsExactly(high, low);
        assertThat(impact.signalRanking()).hasSize(2);
        assertThat(impact.signalRanking().get(0).id()).isEqualTo(high);
        assertThat(impact.signalRanking().get(0).weight())
                .isGreaterThan(impact.signalRanking().get(1).weight());
    }
}
