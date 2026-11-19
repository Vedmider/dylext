package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.domain.port.in.RepoCatalogUseCase;
import io.github.vedmider.dylext.domain.port.out.DigestStore;
import io.github.vedmider.dylext.domain.port.out.DigestStore.FileStat;
import io.github.vedmider.dylext.domain.port.out.VcsClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

@Service
public class RepoCatalogService implements RepoCatalogUseCase {

    private static final Map<String, String> BUILD_FILES = Map.ofEntries(
            Map.entry("pom.xml", "maven"), Map.entry("build.gradle", "gradle"),
            Map.entry("build.gradle.kts", "gradle"), Map.entry("settings.gradle", "gradle"),
            Map.entry("settings.gradle.kts", "gradle"), Map.entry("go.mod", "go"),
            Map.entry("go.work", "go-workspace"), Map.entry("package.json", "node"),
            Map.entry("pyproject.toml", "python"), Map.entry("setup.py", "python"),
            Map.entry("Cargo.toml", "rust"));

    private final DigestStore store;
    private final VcsClient vcs;
    private final LanguageRouter router;
    private final FreshnessGate freshness;

    public RepoCatalogService(DigestStore store, VcsClient vcs, LanguageRouter router,
                              FreshnessGate freshness) {
        this.store = store;
        this.vcs = vcs;
        this.router = router;
        this.freshness = freshness;
    }

    @Override
    public List<RepoInfo> listRepos() {
        return store.listRepos().stream().map(r -> new RepoInfo(r.repo(), r.root(), branch(r.root()),
                r.cursor(), r.files(), r.symbols())).toList();
    }

    @Override
    public io.github.vedmider.dylext.domain.model.EpochRef epochFor(String repo) {
        String commit = null;
        Optional<String> root = store.repoRoot(repo);
        if (root.isPresent() && vcs.isRepo(root.get())) {
            commit = vcs.headSha(root.get());   // live HEAD: a new commit must bust the cache key
        }
        if (commit == null) {
            commit = store.repoCursor(repo).orElse(null);   // non-git or unindexed: fall back to the cursor
        }
        return io.github.vedmider.dylext.domain.model.EpochRef.committed(repo, commit);
    }

    @Override
    public RepoOverview overview(String repo) {
        freshness.ensureFresh(repo);
        DigestStore.RepoRow row = store.listRepos().stream().filter(r -> r.repo().equals(repo)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("repo not indexed: " + repo
                        + " - call index {repo, root} first"));
        List<FileStat> files = store.fileStats(repo);
        Map<String, Integer> languages = new TreeMap<>();
        Map<String, int[]> dirs = new TreeMap<>();
        for (FileStat f : files) {
            languages.merge(f.language(), 1, Integer::sum);
            int slash = f.path().indexOf('/');
            int[] d = dirs.computeIfAbsent(slash < 0 ? "." : f.path().substring(0, slash), k -> new int[2]);
            d[0]++;
            d[1] += f.symbols();
        }
        List<DirStat> top = new ArrayList<>();
        dirs.forEach((dir, c) -> top.add(new DirStat(dir, c[0], c[1])));
        top.sort(Comparator.comparingInt(DirStat::symbols).reversed().thenComparing(DirStat::dir));
        return new RepoOverview(repo, row.root(), branch(row.root()), row.cursor(), row.files(), row.symbols(),
                sortedByCount(languages), unsupportedLanguages(row.root()), modules(row.root()), top);
    }

    /** Source languages PRESENT in the working tree that Dylext cannot parse (no/unavailable parser), by count. */
    private Map<String, Integer> unsupportedLanguages(String root) {
        List<String> files = vcs.listWorkingFiles(root).orElseGet(() -> walk(root));
        Map<String, Integer> out = new TreeMap<>();
        for (String f : files) {
            if (router.supports(f)) {
                continue;
            }
            String key = router.unsupportedKey(f);
            if (key != null) {
                out.merge(key, 1, Integer::sum);
            }
        }
        return sortedByCount(out);
    }

    @Override
    public List<LanguageInfo> languages() {
        return router.describe();
    }

    private String branch(String root) {
        try {
            return vcs.isRepo(root) ? vcs.currentBranch(root) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private List<Module> modules(String root) {
        List<String> files = vcs.listWorkingFiles(root).orElseGet(() -> walk(root));
        List<Module> out = new ArrayList<>();
        for (String f : files) {
            int slash = f.lastIndexOf('/');
            String kind = BUILD_FILES.get(slash < 0 ? f : f.substring(slash + 1));
            if (kind != null) {
                out.add(new Module(slash < 0 ? "." : f.substring(0, slash), kind));
            }
        }
        out.sort(Comparator.comparing(Module::path).thenComparing(Module::kind));
        return out;
    }

    private static List<String> walk(String root) {
        Path base = Path.of(root);
        try (Stream<Path> s = Files.walk(base)) {
            return s.filter(Files::isRegularFile).map(p -> base.relativize(p).toString().replace('\\', '/'))
                    .filter(p -> !p.startsWith(".git/") && !p.contains("/node_modules/")
                            && !p.contains("/target/")).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Map<String, Integer> sortedByCount(Map<String, Integer> m) {
        Map<String, Integer> out = new LinkedHashMap<>();
        m.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey())).forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }
}
