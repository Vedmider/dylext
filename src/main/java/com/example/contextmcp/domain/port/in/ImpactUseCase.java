package com.example.contextmcp.domain.port.in;

import java.util.List;

/** Inbound port: structural blast radius of a symbol — who calls it and which tests cover it. */
public interface ImpactUseCase {

    Impact impactOf(String symbolId);

    record Impact(String symbolId, List<String> callers, List<String> tests) {
    }
}
