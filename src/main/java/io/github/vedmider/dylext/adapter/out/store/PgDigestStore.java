package io.github.vedmider.dylext.adapter.out.store;

import io.github.vedmider.dylext.domain.model.CodeEdge;
import io.github.vedmider.dylext.domain.model.Skeleton;
import io.github.vedmider.dylext.domain.model.Summary;
import io.github.vedmider.dylext.domain.model.Symbol;
import io.github.vedmider.dylext.domain.model.SymbolKind;
import io.github.vedmider.dylext.domain.port.out.DigestStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class PgDigestStore implements DigestStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final String resolveMode;

    public PgDigestStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                         @org.springframework.beans.factory.annotation.Value("${dylext.edges.resolve:type}")
                         String resolveMode) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.resolveMode = resolveMode;
    }

    /** One commit for a whole file's writes instead of one fsync per statement. */
    @Override
    public void atomically(Runnable work) {
        tx.executeWithoutResult(status -> work.run());
    }

    @Override
    public void clearRepo(String repo) {
        atomically(() -> {
            jdbc.update("DELETE FROM edges WHERE repo=?", repo);
            jdbc.update("DELETE FROM symbols WHERE repo=?", repo);   // cascades skeletons + summaries
            jdbc.update("UPDATE repo_state SET git_cursor=NULL WHERE repo=?", repo);
        });
    }

    @Override
    public void upsert(Symbol s, Skeleton sk) {
        jdbc.update("""
                INSERT INTO symbols (id, repo, file_path, language, kind, name, symbol_path,
                                     signature, doc_first_line, start_line, end_line, content_hash, arity)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (id) DO UPDATE SET
                    repo=EXCLUDED.repo, file_path=EXCLUDED.file_path, language=EXCLUDED.language,
                    kind=EXCLUDED.kind, name=EXCLUDED.name, symbol_path=EXCLUDED.symbol_path,
                    signature=EXCLUDED.signature, doc_first_line=EXCLUDED.doc_first_line,
                    start_line=EXCLUDED.start_line, end_line=EXCLUDED.end_line,
                    content_hash=EXCLUDED.content_hash, arity=EXCLUDED.arity
                """,
                s.id(), s.repo(), s.filePath(), s.language(), s.kind().name(), s.name(),
                s.symbolPath(), s.signature(), s.docFirstLine(), s.startLine(), s.endLine(),
                s.contentHash(), arityOf(s.symbolPath()));
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
    public List<Symbol> searchSymbols(String repo, String query, int topK, String pathPrefix) {
        String prefix = normalizePrefix(pathPrefix);
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
                  AND left(file_path, length(?::text)) = ?::text
                ORDER BY relevance DESC, name
                LIMIT ?
                """, SYMBOL,
                query.toLowerCase(), like, like, like,
                repo, like, like, like, prefix, prefix, topK);
    }

    private static String normalizePrefix(String p) {
        if (p == null) {
            return "";
        }
        String s = p.trim().replace('\\', '/');
        while (s.startsWith("./") || s.startsWith("/")) {
            s = s.startsWith("./") ? s.substring(2) : s.substring(1);
        }
        return s;
    }

    @Override
    public List<RepoRow> listRepos() {
        return jdbc.query("""
                SELECT r.repo, r.root, r.git_cursor,
                  (SELECT count(DISTINCT file_path) FROM symbols s WHERE s.repo = r.repo) AS files,
                  (SELECT count(*) FROM symbols s WHERE s.repo = r.repo AND s.symbol_path <> '<module>') AS symbols
                FROM repo_state r ORDER BY r.repo
                """, (rs, i) -> new RepoRow(rs.getString("repo"), rs.getString("root"),
                rs.getString("git_cursor"), rs.getInt("files"), rs.getInt("symbols")));
    }

    @Override
    public List<FileStat> fileStats(String repo) {
        return jdbc.query("""
                SELECT file_path, min(language) AS language,
                       count(*) FILTER (WHERE symbol_path <> '<module>') AS symbols
                FROM symbols WHERE repo = ? GROUP BY file_path ORDER BY file_path
                """, (rs, i) -> new FileStat(rs.getString("file_path"), rs.getString("language"),
                rs.getInt("symbols")), repo);
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
    public int indexGeneration(String repo) {
        return jdbc.query("SELECT index_generation FROM repo_state WHERE repo=?",
                (rs, i) -> rs.getInt(1), repo).stream().findFirst().orElse(0);
    }

    @Override
    public void saveIndexGeneration(String repo, int generation) {
        jdbc.update("UPDATE repo_state SET index_generation=? WHERE repo=?", generation, repo);
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
            jdbc.update("INSERT INTO edges (repo, src_id, dst_id, dst_name, kind, dst_arity, dst_recv, "
                    + "dst_param_types) VALUES (?,?,?,?,?,?,?,?)",
                    e.repo(), e.srcId(), e.dstId(), e.dstName(), e.kind(), e.dstArity(), e.dstRecv(),
                    e.dstParamTypes());
        }
    }

    @Override
    public void resolvePendingEdges(String repo) {
        if ("name".equalsIgnoreCase(resolveMode)) {
            jdbc.update("""
                    UPDATE edges e SET dst_id = s.id
                    FROM symbols s
                    WHERE e.dst_id IS NULL AND e.repo = ? AND s.repo = e.repo
                      AND s.name = e.dst_name AND s.symbol_path <> '<module>' AND s.kind <> 'FIELD'
                    """, repo);
            return;
        }
        if ("type".equalsIgnoreCase(resolveMode)) {
            resolveTypeAware(repo);
            return;
        }
        // arity mode: resolve each pending edge to the best match (arity-exact, then receiver-match, then stable id)
        jdbc.update("""
                UPDATE edges e SET dst_id = (
                    SELECT s.id FROM symbols s
                    WHERE s.repo = e.repo AND s.symbol_path <> '<module>' AND s.name = e.dst_name
                      AND s.kind <> 'FIELD'
                      AND (e.dst_arity IS NULL OR s.arity IS NULL OR s.arity = e.dst_arity)
                    ORDER BY (s.arity IS NOT DISTINCT FROM e.dst_arity) DESC,
                             (e.dst_recv IS NOT NULL AND s.symbol_path LIKE e.dst_recv || '#%') DESC,
                             s.id
                    LIMIT 1)
                WHERE e.dst_id IS NULL AND e.repo = ?
                  AND EXISTS (SELECT 1 FROM symbols s2
                              WHERE s2.repo = e.repo AND s2.name = e.dst_name AND s2.symbol_path <> '<module>'
                                AND s2.kind <> 'FIELD')
                """, repo);
    }

    /**
     * Type-aware resolution (tier A + C): arity narrows candidates; inferred call-site param types pick the
     * exact overload when they match exactly one (A); an un-splittable same-arity tie fans out to all tied
     * candidates, each marked {@code ambiguous} (C) — so a real caller is never dropped as "missing".
     */
    private void resolveTypeAware(String repo) {
        List<PendingEdge> pending = jdbc.query(
                "SELECT e.id, e.dst_name, e.dst_arity, e.dst_param_types, e.dst_recv, s.language, s.symbol_path "
                        + "FROM edges e "
                        + "JOIN symbols s ON s.id = e.src_id WHERE e.dst_id IS NULL AND e.repo = ?",
                (rs, i) -> new PendingEdge(rs.getLong("id"), rs.getString("dst_name"),
                        (Integer) rs.getObject("dst_arity"), rs.getString("dst_param_types"),
                        rs.getString("dst_recv"), rs.getString("language"), rs.getString("symbol_path")),
                repo);
        for (PendingEdge e : pending) {
            List<Candidate> candidates = jdbc.query(
                    "SELECT id, symbol_path, arity, kind FROM symbols WHERE repo = ? AND name = ? "
                            + "AND symbol_path <> '<module>' AND kind <> 'FIELD' AND language = ?",
                    (rs, i) -> new Candidate(rs.getString("id"), rs.getString("symbol_path"),
                            (Integer) rs.getObject("arity"), rs.getString("kind")),
                    repo, e.dstName(), e.language());
            if (typeLike(e.recv())) {
                candidates = candidates.stream().filter(c -> c.ownedBy(e.recv())).toList();   // none = external type
            } else if ("this".equals(e.recv())) {
                List<Candidate> own = candidates.stream().filter(c -> c.enclosing(e.srcPath())).toList();
                candidates = own.isEmpty() ? candidates : own;   // none = inherited from a supertype
            }
            if (candidates.stream().anyMatch(Candidate::callable)) {
                candidates = candidates.stream().filter(Candidate::callable).toList();   // ctor beats its class
            }
            if (candidates.isEmpty()) {
                continue;   // truly unresolved: leave pending (as in arity mode)
            }
            List<Candidate> byArity = candidates.stream()
                    .filter(c -> e.arity() == null || c.arity() == null || c.arity().equals(e.arity()))
                    .toList();
            if (byArity.isEmpty() && "java".equalsIgnoreCase(e.language())) {
                continue;   // Java arity is exact: no candidate fits, so the target is external
            }
            List<Candidate> pool = byArity.isEmpty() ? candidates : byArity;
            if (pool.size() == 1) {
                jdbc.update("UPDATE edges SET dst_id = ? WHERE id = ?", pool.get(0).id(), e.id());
                continue;
            }
            List<Candidate> byType = e.paramTypes() == null ? List.of() : pool.stream()
                    .filter(c -> e.paramTypes().equals(paramTypesOf(c.symbolPath())))
                    .toList();
            if (byType.size() == 1) {   // tier A: exact overload by inferred param types
                jdbc.update("UPDATE edges SET dst_id = ? WHERE id = ?", byType.get(0).id(), e.id());
                continue;
            }
            fanOutAmbiguous(e.id(), repo, pool);   // tier C: un-splittable tie
        }
    }

    /** Point the edge at the first candidate and clone an ambiguous edge for each remaining one. */
    private void fanOutAmbiguous(long edgeId, String repo, List<Candidate> pool) {
        jdbc.update("UPDATE edges SET dst_id = ?, ambiguous = true WHERE id = ?", pool.get(0).id(), edgeId);
        for (int i = 1; i < pool.size(); i++) {
            jdbc.update("""
                    INSERT INTO edges (repo, src_id, dst_id, dst_name, kind, dst_arity, dst_recv,
                                       dst_param_types, ambiguous)
                    SELECT repo, src_id, ?, dst_name, kind, dst_arity, dst_recv, dst_param_types, true
                    FROM edges WHERE id = ?
                    """, pool.get(i).id(), edgeId);
        }
    }

    /** The CSV of simple param-type names from a method/constructor symbolPath "...#name(T1,T2)"; "" for (). */
    static String paramTypesOf(String symbolPath) {
        int open = symbolPath.lastIndexOf('(');
        int close = symbolPath.lastIndexOf(')');
        if (open < 0 || close < open) {
            return null;
        }
        return symbolPath.substring(open + 1, close).trim();
    }

    private static boolean typeLike(String recv) {
        return recv != null && !recv.isEmpty() && Character.isUpperCase(recv.charAt(0)) && !recv.contains(".")
                && !recv.equals(recv.toUpperCase(java.util.Locale.ROOT));
    }

    private record PendingEdge(long id, String dstName, Integer arity, String paramTypes, String recv,
                               String language, String srcPath) {
    }

    private record Candidate(String id, String symbolPath, Integer arity, String kind) {
        boolean callable() {
            return "METHOD".equals(kind) || "CONSTRUCTOR".equals(kind);
        }

        boolean ownedBy(String type) {
            String owner = owner(symbolPath);
            return owner.equals(type) || owner.endsWith("." + type);
        }

        boolean enclosing(String callerPath) {
            String mine = owner(symbolPath);
            String caller = owner(callerPath);
            return caller.equals(mine) || caller.startsWith(mine + ".");
        }

        private static String owner(String path) {
            int hash = path.indexOf('#');
            return hash >= 0 ? path.substring(0, hash) : path;
        }
    }

    /** Param count encoded in a method/constructor symbolPath "...#name(T1,T2)"; null when no parens. */
    static Integer arityOf(String symbolPath) {
        int open = symbolPath.lastIndexOf('(');
        int close = symbolPath.lastIndexOf(')');
        if (open < 0 || close < open) {
            return null;
        }
        String inner = symbolPath.substring(open + 1, close).trim();
        if (inner.isEmpty()) {
            return 0;
        }
        int depth = 0;
        int count = 1;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '<' || c == '(' || c == '[') {
                depth++;
            } else if (c == '>' || c == ')' || c == ']') {
                depth--;
            } else if (c == ',' && depth == 0) {
                count++;
            }
        }
        return count;
    }

    @Override
    public List<String> callersOf(String symbolId) {
        return jdbc.queryForList(
                "SELECT DISTINCT src_id FROM edges WHERE dst_id=? ORDER BY src_id",
                String.class, symbolId);
    }

    @Override
    public boolean hasAmbiguousCallers(String symbolId) {
        Boolean any = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM edges WHERE dst_id=? AND ambiguous)",
                Boolean.class, symbolId);
        return Boolean.TRUE.equals(any);
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
