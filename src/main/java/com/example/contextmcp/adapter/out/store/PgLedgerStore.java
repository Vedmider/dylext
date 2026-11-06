package com.example.contextmcp.adapter.out.store;

import com.example.contextmcp.domain.model.LedgerEntry;
import com.example.contextmcp.domain.port.out.LedgerStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PgLedgerStore implements LedgerStore {

    private final JdbcTemplate jdbc;

    public PgLedgerStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(LedgerEntry e) {
        jdbc.update("""
                INSERT INTO ledger (task_id, tool, tokens_served, baseline_tokens)
                VALUES (?,?,?,?)
                """, e.taskId(), e.tool(), e.tokensServed(), e.baselineTokens());
    }

    @Override
    public Totals totals() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS calls,
                       COALESCE(SUM(tokens_served),0) AS served,
                       COALESCE(SUM(baseline_tokens),0) AS baseline
                FROM ledger
                """, (rs, i) -> new Totals(rs.getLong("calls"),
                rs.getLong("served"), rs.getLong("baseline")));
    }
}
