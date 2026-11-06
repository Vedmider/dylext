package com.example.contextmcp.application;

import com.example.contextmcp.domain.port.in.ImpactUseCase;
import com.example.contextmcp.domain.port.out.DigestStore;
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
