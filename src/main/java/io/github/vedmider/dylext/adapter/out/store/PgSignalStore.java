package io.github.vedmider.dylext.adapter.out.store;

import io.github.vedmider.dylext.domain.model.SymbolSignal;
import io.github.vedmider.dylext.domain.port.out.SignalStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class PgSignalStore implements SignalStore {

    private final JdbcTemplate jdbc;

    public PgSignalStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int upsert(String repo, List<SymbolSignal> signals) {
        for (SymbolSignal s : signals) {
            jdbc.update("""
                    INSERT INTO symbol_signal(repo, symbol_id, coverage, call_freq, weight)
                    VALUES (?,?,?,?,?)
                    ON CONFLICT (repo, symbol_id)
                    DO UPDATE SET coverage=EXCLUDED.coverage, call_freq=EXCLUDED.call_freq, weight=EXCLUDED.weight
                    """, repo, s.symbolId(), s.coverage(), s.callFrequency(), s.weight());
        }
        return signals.size();
    }

    @Override
    public Map<String, SymbolSignal> forSymbols(Collection<String> symbolIds) {
        Map<String, SymbolSignal> out = new LinkedHashMap<>();
        if (symbolIds == null || symbolIds.isEmpty()) {
            return out;
        }
        List<String> ids = List.copyOf(symbolIds);
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        jdbc.query("SELECT symbol_id, coverage, call_freq, weight FROM symbol_signal WHERE symbol_id IN ("
                        + placeholders + ")",
                rs -> {
                    out.put(rs.getString("symbol_id"), new SymbolSignal(rs.getString("symbol_id"),
                            rs.getDouble("coverage"), rs.getDouble("call_freq"), rs.getDouble("weight")));
                }, ids.toArray());
        return out;
    }
}
