package io.github.vedmider.dylext.adapter.out.store;

import io.github.vedmider.dylext.domain.port.out.KnownRepos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PgKnownRepos implements KnownRepos {

    private final JdbcTemplate jdbc;

    public PgKnownRepos(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isKnown(String repo) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM repo_state WHERE repo = ?)", Boolean.class, repo));
    }
}
