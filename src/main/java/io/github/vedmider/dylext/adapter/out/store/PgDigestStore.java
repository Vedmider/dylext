package io.github.vedmider.dylext.adapter.out.store;

import io.github.vedmider.dylext.domain.model.CodeEdge;
import io.github.vedmider.dylext.domain.model.Skeleton;
import io.github.vedmider.dylext.domain.model.Summary;
import io.github.vedmider.dylext.domain.model.Symbol;
import io.github.vedmider.dylext.domain.model.SymbolKind;
import io.github.vedmider.dylext.domain.port.out.DigestStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class PgDigestStore implements DigestStore {

    private final JdbcTemplate jdbc;

    public PgDigestStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsert(Symbol s, Skeleton sk) {
        jdbc.update("""
                INSERT INTO symbols (id, repo, file_path, language, kind, name, symbol_path,
                                     signature, doc_first_line, start_line, end_line, content_hash)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (id) DO UPDATE SET
                    repo=EXCLUDED.repo, file_path=EXCLUDED.file_path, language=EXCLUDED.language,
                    kind=EXCLUDED.kind, name=EXCLUDED.name, symbol_path=EXCLUDED.symbol_path,
                    signature=EXCLUDED.signature, doc_first_line=EXCLUDED.doc_first_line,
                    start_line=EXCLUDED.start_line, end_line=EXCLUDED.end_line,
                    content_hash=EXCLUDED.content_hash
                """,
                s.id(), s.repo(), s.filePath(), s.language(), s.kind().name(), s.name(),
                s.symbolPath(), s.signature(), s.docFirstLine(), s.startLine(), s.endLine(),
                s.contentHash());
        jdbc.update("""
                INSERT INTO skeletons (symbol_id, content_hash, skeleton_text, token_count)
                VALUES (?,?,?,?)
                ON CONFLICT (symbol_id) DO UPDATE SET
                    content_hash=EXCLUDED.content_hash, skeleton_text=EXCLUDED.skeleton_text,
                    token_count=EXCLUDED.token_count
                """,
                sk.symbolId(), sk.contentHash(), sk.text(), sk.tokenCount());
    }

    @Override
    public Optional<Symbol> findSymbol(String symbolId) {
        return jdbc.query("SELECT * FROM symbols WHERE id=?", SYMBOL, symbolId).stream().findFirst();
    }

    @Override
    public Optional<Skeleton> findSkeleton(String symbolId) {
        return jdbc.query("SELECT * FROM skeletons WHERE symbol_id=?", SKELETON, symbolId)
                .stream().findFirst();
    }

    @Override
    public List<Symbol> symbolsInFile(String repo, String filePath) {
        return jdbc.query("SELECT * FROM symbols WHERE repo=? AND file_path=? ORDER BY start_line",
                SYMBOL, repo, filePath);
    }

    @Override
    public Optional<String> fileContentHash(String repo, String filePath) {
        return jdbc.query(
                "SELECT content_hash FROM symbols WHERE repo=? AND file_path=? AND symbol_path='<module>'",
                (rs, i) -> rs.getString(1), repo, filePath).stream().findFirst();
    }

    @Override
    public List<String> filesInRepo(String repo) {
        return jdbc.queryForList(
                "SELECT DISTINCT file_path FROM symbols WHERE repo=? ORDER BY file_path",
                String.class, repo);
    }

    @Override
    public List<Symbol> searchSymbols(String repo, String query, int topK) {
        String like = "%" + query.toLowerCase() + "%";
        return jdbc.query("""
                SELECT *, (
                    (CASE WHEN lower(name) = ? THEN 100 ELSE 0 END) +
                    (CASE WHEN lower(name) LIKE ? THEN 40 ELSE 0 END) +
                    (CASE WHEN lower(signature) LIKE ? THEN 10 ELSE 0 END) +
                    (CASE WHEN lower(doc_first_line) LIKE ? THEN 5 ELSE 0 END)
                ) AS relevance
                FROM symbols
                WHERE repo = ? AND symbol_path <> '<module>'
                  AND (lower(name) LIKE ? OR lower(signature) LIKE ? OR lower(doc_first_line) LIKE ?)
                ORDER BY relevance DESC, name
                LIMIT ?
                """, SYMBOL,
                query.toLowerCase(), like, like, like,
                repo, like, like, like, topK);
    }

    @Override
    public void saveSummary(Summary sm) {
        jdbc.update("""
                INSERT INTO summaries (symbol_id, content_hash, summary_text, build_tokens)
                VALUES (?,?,?,?)
                ON CONFLICT (symbol_id) DO UPDATE SET
                    content_hash=EXCLUDED.content_hash, summary_text=EXCLUDED.summary_text,
                    build_tokens=EXCLUDED.build_tokens
                """,
                sm.symbolId(), sm.contentHash(), sm.text(), sm.buildTokens());
    }

    @Override
    public Optional<Summary> findSummary(String symbolId) {
        return jdbc.query("SELECT * FROM summaries WHERE symbol_id=?", SUMMARY, symbolId)
                .stream().findFirst();
    }

    @Override
    public void deleteSymbol(String symbolId) {
        jdbc.update("DELETE FROM symbols WHERE id=?", symbolId);
    }

    @Override
    public void deleteFile(String repo, String filePath) {
        jdbc.update("DELETE FROM symbols WHERE repo=? AND file_path=?", repo, filePath);
    }

    @Override
    public void saveRepoRoot(String repo, String root) {
        jdbc.update("""
                INSERT INTO repo_state (repo, root) VALUES (?,?)
                ON CONFLICT (repo) DO UPDATE SET root=EXCLUDED.root, updated_at=now()
                """, repo, root);
    }

    @Override
    public Optional<String> repoRoot(String repo) {
        return jdbc.query("SELECT root FROM repo_state WHERE repo=?",
                (rs, i) -> rs.getString(1), repo).stream().findFirst();
    }

    @Override
    public void saveRepoCursor(String repo, String sha) {
        jdbc.update("UPDATE repo_state SET git_cursor=?, updated_at=now() WHERE repo=?", sha, repo);
    }

    @Override
    public Optional<String> repoCursor(String repo) {
        return jdbc.query("SELECT git_cursor FROM repo_state WHERE repo=?",
                (rs, i) -> rs.getString(1), repo).stream()
                .filter(java.util.Objects::nonNull).findFirst();
    }

    @Override
    public void replaceEdgesFrom(String srcId, List<CodeEdge> edges) {
        jdbc.update("DELETE FROM edges WHERE src_id=?", srcId);
        for (CodeEdge e : edges) {
            jdbc.update("INSERT INTO edges (repo, src_id, dst_id, dst_name, kind) VALUES (?,?,?,?,?)",
                    e.repo(), e.srcId(), e.dstId(), e.dstName(), e.kind());
        }
    }

    @Override
    public void resolvePendingEdges(String repo) {
        jdbc.update("""
                UPDATE edges e SET dst_id = s.id
                FROM symbols s
                WHERE e.dst_id IS NULL
                  AND e.repo = ?
                  AND s.repo = e.repo
                  AND s.name = e.dst_name
                  AND s.symbol_path <> '<module>'
                """, repo);
    }

    @Override
    public List<String> callersOf(String symbolId) {
        return jdbc.queryForList(
                "SELECT DISTINCT src_id FROM edges WHERE dst_id=? ORDER BY src_id",
                String.class, symbolId);
    }

    @Override
    public List<String> testsFor(String symbolId) {
        return jdbc.queryForList("""
                SELECT DISTINCT e.src_id FROM edges e
                JOIN symbols s ON s.id = e.src_id
                WHERE e.dst_id = ? AND lower(s.file_path) LIKE '%test%'
                ORDER BY e.src_id
                """, String.class, symbolId);
    }

    private static final RowMapper<Symbol> SYMBOL = (rs, i) -> new Symbol(
            rs.getString("id"), rs.getString("repo"), rs.getString("file_path"),
            rs.getString("language"), SymbolKind.valueOf(rs.getString("kind")),
            rs.getString("name"), rs.getString("symbol_path"), rs.getString("signature"),
            rs.getString("doc_first_line"), rs.getInt("start_line"), rs.getInt("end_line"),
            List.of(), rs.getString("content_hash"));

    private static final RowMapper<Skeleton> SKELETON = (rs, i) -> new Skeleton(
            rs.getString("symbol_id"), rs.getString("content_hash"),
            rs.getString("skeleton_text"), rs.getInt("token_count"));

    private static final RowMapper<Summary> SUMMARY = (rs, i) -> new Summary(
            rs.getString("symbol_id"), rs.getString("content_hash"),
            rs.getString("summary_text"), rs.getInt("build_tokens"));
}
