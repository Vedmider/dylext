package io.github.vedmider.dylext.adapter.out.store;

import io.github.vedmider.dylext.domain.model.Session;
import io.github.vedmider.dylext.domain.model.SessionEntry;
import io.github.vedmider.dylext.domain.port.out.SessionStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/** Postgres/JSONB session store: two entry layers (decisions durable, facts bounded), keyed by id. */
@Repository
public class PgSessionStore implements SessionStore {

    private static final TypeReference<List<SessionEntry>> ENTRIES = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public PgSessionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Session s) {
        jdbc.update("""
                INSERT INTO session (id, task, status, repos, decisions, facts, updated_at)
                VALUES (?,?,?,?::jsonb,?::jsonb,?::jsonb, now())
                ON CONFLICT (id) DO UPDATE SET
                    task=EXCLUDED.task, status=EXCLUDED.status, repos=EXCLUDED.repos,
                    decisions=EXCLUDED.decisions, facts=EXCLUDED.facts, updated_at=now()
                """,
                s.id(), s.task(), s.status().name(),
                w(s.repos()), w(s.decisions()), w(s.facts()));
    }

    @Override
    public void appendFact(String id, SessionEntry fact, int maxFacts) {
        Session s = load(id);
        List<SessionEntry> facts = new ArrayList<>(s.facts());
        facts.add(fact);
        int n = facts.size();
        int keep = Math.max(maxFacts, 1);
        if (n > keep) {
            facts = new ArrayList<>(facts.subList(n - keep, n));
        }
        save(new Session(s.id(), s.task(), s.status(), s.repos(), s.decisions(), facts, s.updatedAt()));
    }

    @Override
    public void appendDecisions(String id, List<SessionEntry> entries) {
        Session s = load(id);
        List<SessionEntry> decisions = new ArrayList<>(s.decisions());
        decisions.addAll(entries);
        save(new Session(s.id(), s.task(), s.status(), s.repos(), decisions, s.facts(), s.updatedAt()));
    }

    @Override
    public Optional<Session> findById(String id) {
        return jdbc.query("SELECT * FROM session WHERE id=?", FULL, id).stream().findFirst();
    }

    @Override
    public List<Session.SessionHeader> listHeaders(String repoFilter, Session.Status statusFilter,
                                                   String textFilter, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, task, status, repos,
                       jsonb_array_length(decisions) AS dcount,
                       jsonb_array_length(facts)     AS fcount,
                       updated_at
                FROM session WHERE 1=1""");
        List<Object> args = new ArrayList<>();
        if (repoFilter != null && !repoFilter.isBlank()) {
            sql.append(" AND repos @> ?::jsonb");
            args.add(w(List.of(repoFilter)));
        }
        if (statusFilter != null) {
            sql.append(" AND status=?");
            args.add(statusFilter.name());
        }
        if (textFilter != null && !textFilter.isBlank()) {
            sql.append(" AND task ILIKE ?");
            args.add("%" + textFilter + "%");
        }
        sql.append(" ORDER BY updated_at DESC LIMIT ?");
        args.add(limit <= 0 ? 50 : limit);
        return jdbc.query(sql.toString(), HEADER, args.toArray());
    }

    @Override
    public void deleteById(String id) {
        jdbc.update("DELETE FROM session WHERE id=?", id);
    }

    @Override
    public long count() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM session", Long.class);
        return n == null ? 0 : n;
    }

    @Override
    public int purge(java.time.Instant closedBefore, java.time.Instant idleBefore) {
        return jdbc.update("""
                DELETE FROM session
                WHERE (status='DONE' AND updated_at < ?)
                   OR (status='ACTIVE' AND updated_at < ?)
                """, java.sql.Timestamp.from(closedBefore), java.sql.Timestamp.from(idleBefore));
    }

    @Override
    public List<String> oldestIds(int n) {
        return jdbc.queryForList(
                "SELECT id FROM session ORDER BY updated_at ASC LIMIT ?", String.class, Math.max(n, 0));
    }

    private Session load(String id) {
        return findById(id).orElseThrow(() -> new NoSuchElementException("unknown session: " + id));
    }

    private String w(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("serializing session json", e);
        }
    }

    private List<SessionEntry> entries(String jsonText) {
        try {
            return jsonText == null ? List.of() : json.readValue(jsonText, ENTRIES);
        } catch (Exception e) {
            throw new IllegalStateException("reading session json", e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> repos(String jsonText) {
        try {
            return jsonText == null ? List.of()
                    : json.readValue(jsonText, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("reading repos json", e);
        }
    }

    private final RowMapper<Session> FULL = (rs, i) -> new Session(
            rs.getString("id"), rs.getString("task"),
            Session.Status.valueOf(rs.getString("status")),
            repos(rs.getString("repos")),
            entries(rs.getString("decisions")),
            entries(rs.getString("facts")),
            rs.getTimestamp("updated_at").toInstant());

    private final RowMapper<Session.SessionHeader> HEADER = (rs, i) -> new Session.SessionHeader(
            rs.getString("id"), rs.getString("task"),
            Session.Status.valueOf(rs.getString("status")),
            repos(rs.getString("repos")),
            rs.getInt("dcount"), rs.getInt("fcount"),
            rs.getTimestamp("updated_at").toInstant());
}
