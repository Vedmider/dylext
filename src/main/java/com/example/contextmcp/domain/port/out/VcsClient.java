package com.example.contextmcp.domain.port.out;

import java.util.List;

/** In-process git access for dynamic context. A non-git root reports {@link #isRepo} false. */
public interface VcsClient {

    boolean isRepo(String root);

    /** Current committed HEAD sha, or null if not a repo / no commits. */
    String headSha(String root);

    /** Paths changed between {@code oldSha} and HEAD (added/modified/deleted). */
    List<String> changedFilesSince(String root, String oldSha);

    /** Recent commits touching {@code pathFilter} (null/blank = whole repo), newest first. */
    List<Commit> recentCommits(String root, String pathFilter, int limit);

    /** Uncommitted changes vs HEAD (staged + unstaged), optionally filtered to {@code pathFilter}. */
    List<WorkingChange> workingChanges(String root, String pathFilter);

    record Commit(String sha, String author, String when, String message, List<String> files) {
    }

    record WorkingChange(String path, String changeType, String diff) {
    }
}
