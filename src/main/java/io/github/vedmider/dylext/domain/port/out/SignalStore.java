package io.github.vedmider.dylext.domain.port.out;

import io.github.vedmider.dylext.domain.model.SymbolSignal;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Persists optional per-symbol runtime signals and reads them back for ranking. */
public interface SignalStore {

    /** Upsert signals for a repo; returns the number stored. */
    int upsert(String repo, List<SymbolSignal> signals);

    /** Signals for the given symbol ids, keyed by id; absent ids are simply missing from the map. */
    Map<String, SymbolSignal> forSymbols(Collection<String> symbolIds);
}
