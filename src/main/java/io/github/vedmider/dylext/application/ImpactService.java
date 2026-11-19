package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.domain.model.Symbol;
import io.github.vedmider.dylext.domain.model.SymbolKind;
import io.github.vedmider.dylext.domain.port.in.ImpactUseCase;
import io.github.vedmider.dylext.domain.port.in.LedgerUseCase;
import io.github.vedmider.dylext.domain.port.out.DigestStore;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Structural impact: callers + covering tests of a symbol, from the resolved call graph. */
@Service
public class ImpactService implements ImpactUseCase {

    private final DigestStore store;
    private final LedgerUseCase ledger;
    private final LanguageRouter languages;
    private final io.github.vedmider.dylext.domain.port.out.SignalStore signals;
    private final FreshnessGate freshness;

    public ImpactService(DigestStore store, LedgerUseCase ledger, LanguageRouter languages,
                         io.github.vedmider.dylext.domain.port.out.SignalStore signals,
                         FreshnessGate freshness) {
        this.store = store;
        this.ledger = ledger;
        this.languages = languages;
        this.signals = signals;
        this.freshness = freshness;
    }

    @Override
    public Impact impactOf(String symbolId, int limit) {
        freshness.ensureFresh(repoOf(symbolId));
        Symbol sym = store.findSymbol(symbolId).orElse(null);
        io.github.vedmider.dylext.domain.model.CapabilityTier tier =
                sym == null ? null : languages.tierOf(sym.language());
        if (tier != null && !tier.atLeast(io.github.vedmider.dylext.domain.model.CapabilityTier.T2_EDGES)) {
            String note = "impact_of needs call edges (" + io.github.vedmider.dylext.domain.model.CapabilityTier
                    .T2_EDGES.id() + "+); " + sym.language() + " is " + tier.id() + " (" + tier.describes() + ")";
            return new Impact(symbolId, List.of(), List.of(), false, note);
        }
        Impact impact = capToLimit(rankBySignal(sym != null && isAggregate(sym.kind())
                ? aggregate(symbolId, sym)
                : new Impact(symbolId, store.callersOf(symbolId), store.testsFor(symbolId),
                        store.hasAmbiguousCallers(symbolId))), limit);
        String served = String.join("\n", impact.callers()) + "\n" + String.join("\n", impact.tests());
        ledger.recordServed(null, "impact_of", served, wholeFilesOf(impact));
        return impact;
    }

    /** Keep only the top {@code limit} callers (after ranking) for big blast radii; flags truncation. */
    private Impact capToLimit(Impact impact, int limit) {
        if (limit <= 0 || impact.callers().size() <= limit) {
            return impact;
        }
        int total = impact.callers().size();
        List<String> kept = List.copyOf(impact.callers().subList(0, limit));
        List<ImpactUseCase.SignalRank> ranking = impact.signalRanking() == null ? null
                : List.copyOf(impact.signalRanking().subList(0, Math.min(limit, impact.signalRanking().size())));
        return new Impact(impact.symbolId(), kept, impact.tests(), impact.ambiguousOverload(),
                impact.capabilityNote(), ranking, true, total);
    }

    /** Reorder callers by runtime-signal weight (hot & untested first) when signals exist; else unchanged. */
    private Impact rankBySignal(Impact base) {
        if (base.callers().isEmpty()) {
            return base;
        }
        var byId = signals.forSymbols(base.callers());
        if (byId.isEmpty()) {
            return base;   // no signals -> byte-identical to before
        }
        java.util.function.ToDoubleFunction<String> weight =
                id -> byId.containsKey(id) ? byId.get(id).weight() : 0.0;
        List<String> ordered = new java.util.ArrayList<>(base.callers());
        ordered.sort(java.util.Comparator.comparingDouble(weight).reversed());
        List<ImpactUseCase.SignalRank> ranking = ordered.stream()
                .map(id -> new ImpactUseCase.SignalRank(id, weight.applyAsDouble(id))).toList();
        return new Impact(base.symbolId(), ordered, base.tests(), base.ambiguousOverload(),
                base.capabilityNote(), ranking);
    }

    /** Baseline an AI would pay without Dylext: reading each caller/test's file whole to find usages. */
    private String wholeFilesOf(Impact impact) {
        Set<String> ids = new LinkedHashSet<>();
        ids.addAll(impact.callers());
        ids.addAll(impact.tests());
        Set<String> seenFiles = new LinkedHashSet<>();
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            store.findSymbol(id).ifPresent(s -> {
                if (seenFiles.add(s.repo() + "/" + s.filePath())) {
                    store.repoRoot(s.repo()).ifPresent(root ->
                            sb.append(readWhole(Path.of(root).resolve(s.filePath()))).append('\n'));
                }
            });
        }
        return sb.toString();
    }

    private static String readWhole(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return "";
        }
    }

    /** A type has no direct call edges; roll its members' blast radius up, excluding internal members. */
    private Impact aggregate(String classId, Symbol sym) {
        String memberPrefix = classId + "#";
        Set<String> callers = new LinkedHashSet<>(store.callersOf(classId));
        Set<String> tests = new LinkedHashSet<>(store.testsFor(classId));
        boolean ambiguous = store.hasAmbiguousCallers(classId);
        for (Symbol member : store.symbolsInFile(sym.repo(), sym.filePath())) {
            if (member.id().startsWith(memberPrefix)) {
                callers.addAll(store.callersOf(member.id()));
                tests.addAll(store.testsFor(member.id()));
                ambiguous = ambiguous || store.hasAmbiguousCallers(member.id());
            }
        }
        callers.removeIf(id -> id.startsWith(memberPrefix) || id.equals(classId));
        return new Impact(classId, List.copyOf(callers), List.copyOf(tests), ambiguous);
    }

    private static boolean isAggregate(SymbolKind kind) {
        return kind == SymbolKind.CLASS || kind == SymbolKind.INTERFACE
                || kind == SymbolKind.ENUM || kind == SymbolKind.RECORD;
    }

    private static String repoOf(String symbolId) {
        int i = symbolId == null ? -1 : symbolId.indexOf(':');
        return i > 0 ? symbolId.substring(0, i) : null;
    }
}
