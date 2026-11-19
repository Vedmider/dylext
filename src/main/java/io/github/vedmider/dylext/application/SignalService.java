package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.domain.model.SymbolSignal;
import io.github.vedmider.dylext.domain.port.in.SignalUseCase;
import io.github.vedmider.dylext.domain.port.out.SignalStore;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SignalService implements SignalUseCase {

    private final SignalStore store;

    public SignalService(SignalStore store) {
        this.store = store;
    }

    @Override
    public IngestResult ingest(String repo, List<SymbolSignal> signals) {
        if (repo == null || repo.isBlank()) {
            throw new IllegalArgumentException("repo is required");
        }
        // Recompute weight server-side so the ranking policy is ours, not the caller's.
        List<SymbolSignal> weighed = signals.stream()
                .map(s -> SymbolSignal.of(s.symbolId(), s.coverage(), s.callFrequency()))
                .toList();
        return new IngestResult(repo, store.upsert(repo, weighed));
    }
}
