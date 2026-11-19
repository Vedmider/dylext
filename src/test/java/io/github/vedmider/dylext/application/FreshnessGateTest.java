package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.domain.model.IndexGeneration;
import io.github.vedmider.dylext.domain.port.in.SyncUseCase;
import io.github.vedmider.dylext.domain.port.out.DigestStore;
import io.github.vedmider.dylext.domain.port.out.VcsClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FreshnessGateTest {

    @Test
    void aFailedSyncDoesNotFailTheReadButIsCountedPerRepo() {
        DigestStore store = mock(DigestStore.class);
        VcsClient vcs = mock(VcsClient.class);
        SyncUseCase sync = mock(SyncUseCase.class);
        when(store.repoRoot("r")).thenReturn(Optional.of("/tmp/r"));
        when(vcs.isRepo("/tmp/r")).thenReturn(true);
        when(vcs.headSha("/tmp/r")).thenReturn("new");
        when(store.repoCursor("r")).thenReturn(Optional.of("old"));
        when(store.indexGeneration("r")).thenReturn(IndexGeneration.CURRENT);
        when(sync.sync(anyString(), anyString())).thenThrow(new IllegalStateException("boom"));
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();

        new FreshnessGate(store, vcs, sync, true, metrics).ensureFresh("r");

        assertThat(metrics.counter("dylext.freshness.sync.failures", "repo", "r").count()).isEqualTo(1.0);
    }
}
