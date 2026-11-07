package com.example.contextmcp.adapter.out.store;

import com.example.contextmcp.domain.model.Session;
import com.example.contextmcp.domain.port.out.SessionStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Postgres/JSONB session store. List fields are serialized as JSONB; retrieval is by exact id or filter. */
@Repository
public class PgSessionStore implements SessionStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public PgSessionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Session s) {
        jdbc.update("""
                INSERT INTO session (id, task, status, repos, decisions, changes, findings,
                                     open_threads, refs, updated_at)
                VALUES (?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb, now())
                ON CONFLICT (id) DO UPDATE SET
                    task=EXCLUDED.task, status=EXCLUDED.status, repos=EXCLUDED.repos,
                    decisions=EXCLUDED.decisions, changes=EXCLUDED.changes, findings=EXCLUDED.findings,
                    open_threads=EXCLUDED.open_threads, refs=EXCLUDED.refs, updated_at=now()
                """,
                s.id(), s.task(), s.status().name(),
                w(s.repos()), w(s.decisions()), w(s.changes()), w(s.findings()),
                w(s.openThreads()), w(s.refs()));
    }

    @Override
    public Optional<Session> findById(String id) {
        return jdbc.query("SELECT * FROM session WHERE id=?", MAPPER, id).stream().findFirst();
    }

    @Override
    public List<Session> list(String repoFilter, Session.Status statusFilter, String textFilter,
                              int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM session WHERE 1=1");
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
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
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
    public int purge(Instant closedBefore, Instant idleBefore) {
        return jdbc.update("""
                DELETE FROM session
                WHERE (status='DONE' AND updated_at < ?)
                   OR (status='ACTIVE' AND updated_at < ?)
                """, Timestamp.from(closedBefore), Timestamp.from(idleBefore));
    }

    @Override
    public List<String> oldestIds(int n) {
        return jdbc.queryForList(
                "SELECT id FROM session ORDER BY updated_at ASC LIMIT ?", String.class, Math.max(n, 0));
    }

    private String w(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("serializing session json", e);
        }
    }

    private <T> List<T> r(String jsonText, TypeReference<List<T>> type) {
        try {
            return jsonText == null ? List.of() : json.readValue(jsonText, type);
        } catch (Exception e) {
            throw new IllegalStateException("reading session json", e);
        }
    }

    private final RowMapper<Session> MAPPER = (rs, i) -> new Session(
            rs.getString("id"),
            rs.getString("task"),
            Session.Status.valueOf(rs.getString("status")),
            r(rs.getString("repos"), new TypeReference<>() { }),
            r(rs.getString("decisions"), new TypeReference<List<Session.Entry>>() { }),
            r(rs.getString("changes"), new TypeReference<List<Session.Change>>() { }),
            r(rs.getString("findings"), new TypeReference<List<Session.Entry>>() { }),
            r(rs.getString("open_threads"), new TypeReference<List<Session.Entry>>() { }),
            r(rs.getString("refs"), new TypeReference<List<Session.Ref>>() { }),
            rs.getTimestamp("updated_at").toInstant());
}
