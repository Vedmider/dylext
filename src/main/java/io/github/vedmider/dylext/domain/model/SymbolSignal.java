package io.github.vedmider.dylext.domain.model;

/**
 * Runtime features for one symbol, pushed from a coverage/trace export. {@code weight} ranks a symbol
 * by risk — frequently called and poorly covered ranks highest — and reorders impact_of's callers.
 */
public record SymbolSignal(String symbolId, double coverage, double callFrequency, double weight) {

    /** Hot-and-untested first: high call frequency times how much of it is NOT covered. */
    public static double weigh(double coverage, double callFrequency) {
        double c = Math.max(0.0, Math.min(1.0, coverage));
        return Math.max(0.0, callFrequency) * (1.0 - c);
    }

    public static SymbolSignal of(String symbolId, double coverage, double callFrequency) {
        return new SymbolSignal(symbolId, coverage, callFrequency, weigh(coverage, callFrequency));
    }
}
