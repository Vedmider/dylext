package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.domain.port.out.DigestStore;
import io.github.vedmider.dylext.domain.port.out.VcsClient;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DynamicContextServiceNotIndexedTest {

    private final DigestStore store = mock(DigestStore.class);
    private final DynamicContextService svc =
            new DynamicContextService(store, mock(VcsClient.class), mock(FreshnessGate.class));

    @Test
    void workingChangesOnAnUnindexedRepoTellsTheCallerToIndexIt() {
        when(store.repoRoot("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.workingChanges("ghost", null))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("repo not indexed: ghost")
                .hasMessageContaining("index");
    }

    @Test
    void recentChangesOnAnUnindexedRepoTellsTheCallerToIndexIt() {
        when(store.repoRoot("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.recentChanges("ghost", null, 5))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("call index");
    }
}
