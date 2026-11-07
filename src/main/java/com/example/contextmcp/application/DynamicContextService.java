package com.example.contextmcp.application;

import com.example.contextmcp.domain.port.in.DynamicContextUseCase;
import com.example.contextmcp.domain.port.out.DigestStore;
import com.example.contextmcp.domain.port.out.VcsClient;
import com.example.contextmcp.domain.port.out.VcsClient.Commit;
import com.example.contextmcp.domain.port.out.VcsClient.WorkingChange;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;

/** Dynamic context computed on read (never cached): resolves repo root then delegates to git. */
@Service
public class DynamicContextService implements DynamicContextUseCase {

    private final DigestStore store;
    private final VcsClient vcs;
    private final FreshnessGate freshness;

    public DynamicContextService(DigestStore store, VcsClient vcs, FreshnessGate freshness) {
        this.store = store;
        this.vcs = vcs;
        this.freshness = freshness;
    }

    @Override
    public List<Commit> recentChanges(String repo, String pathFilter, int limit) {
        freshness.ensureFresh(repo);
        return vcs.recentCommits(rootOf(repo), pathFilter, limit <= 0 ? 10 : limit);
    }

    @Override
    public List<WorkingChange> workingChanges(String repo, String pathFilter) {
        freshness.ensureFresh(repo);
        return vcs.workingChanges(rootOf(repo), pathFilter);
    }

    private String rootOf(String repo) {
        return store.repoRoot(repo)
                .orElseThrow(() -> new NoSuchElementException("repo not indexed: " + repo));
    }
}
