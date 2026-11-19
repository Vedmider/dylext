package io.github.vedmider.dylext.domain.port.out;

import java.util.List;
import java.util.Optional;

/**
 * In-process git access for dynamic context. A root may be the repo root or any folder inside the
 * work tree (e.g. one module of a multi-module repo): all paths are then relative to that root and
 * results are scoped to it. A non-git root reports {@link #isRepo} false.
 */
public interface VcsClient {

    /** True if {@code root} is, or lies inside, a git work tree. */
    boolean isRepo(String root);

    /** Current committed HEAD sha, or null if not a repo / no commits. */
    String headSha(String root);

    /** Checked-out branch name, {@code "(detached <sha>)"} when detached, null if not a repo. */
    String currentBranch(String root);

    /** Paths (relative to {@code root}) changed between {@code oldSha} and HEAD (added/modified/deleted). */
    List<String> changedFilesSince(String root, String oldSha);

    /** Recent commits touching {@code pathFilter} (null/blank = all of {@code root}), newest first. */
    List<Commit> recentCommits(String root, String pathFilter, int limit);

    /** Uncommitted changes vs HEAD (staged + unstaged + untracked, never ignored files) under {@code root}. */
    List<WorkingChange> workingChanges(String root, String pathFilter);

    /**
     * Files under {@code root} that git would consider: tracked or untracked, but not gitignored and not
     * inside a nested repository. Paths are relative to {@code root}. Empty when not a git work tree.
     */
    Optional<List<String>> listWorkingFiles(String root);

    record Commit(String sha, String author, String when, String message, List<String> files) {
    }

    record WorkingChange(String path, String changeType, String diff) {
    }
}
