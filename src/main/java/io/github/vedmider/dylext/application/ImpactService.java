package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.domain.port.in.ImpactUseCase;
import io.github.vedmider.dylext.domain.port.out.DigestStore;
import org.springframework.stereotype.Service;

/** Structural impact: callers + covering tests of a symbol, from the resolved call graph. */
@Service
public class ImpactService implements ImpactUseCase {

    private final DigestStore store;

    public ImpactService(DigestStore store) {
        this.store = store;
    }

    @Override
    public Impact impactOf(String symbolId) {
        return new Impact(symbolId, store.callersOf(symbolId), store.testsFor(symbolId));
    }
}
