package io.github.vedmider.dylext.domain.port.in;

import io.github.vedmider.dylext.domain.model.SymbolSignal;

import java.util.List;

/** Inbound port: push runtime signals (coverage, call frequency) that weight impact_of ranking. */
public interface SignalUseCase {

    IngestResult ingest(String repo, List<SymbolSignal> signals);

    record IngestResult(String repo, int accepted) {
    }
}
