package io.github.vedmider.dylext.domain.port.in;

import java.util.List;
import java.util.Map;

/** What Dylext knows about: indexed repos, how a repo is laid out (modules), and which languages it can parse. */
public interface RepoCatalogUseCase {

    List<RepoInfo> listRepos();

    /** @throws java.util.NoSuchElementException when the repo was never indexed */
    RepoOverview overview(String repo);

    List<LanguageInfo> languages();

    /** The committed epoch (indexed cursor) a read of {@code repo} is computed against. */
    io.github.vedmider.dylext.domain.model.EpochRef epochFor(String repo);

    record RepoInfo(String repo, String root, String branch, String head, int files, int symbols) {
    }

    record Module(String path, String kind) {
    }

    record DirStat(String dir, int files, int symbols) {
    }

    record RepoOverview(String repo, String root, String branch, String head, int files, int symbols,
                        Map<String, Integer> languages,
                        @com.fasterxml.jackson.annotation.JsonInclude(
                                com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
                        Map<String, Integer> unsupported,
                        List<Module> modules, List<DirStat> topLevel) {
    }

    record LanguageInfo(String language, List<String> extensions, boolean available, String parser,
                        String tier) {
    }
}
