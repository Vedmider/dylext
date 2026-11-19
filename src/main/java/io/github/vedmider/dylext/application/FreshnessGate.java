package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.domain.port.in.SyncUseCase;
import io.github.vedmider.dylext.domain.port.out.DigestStore;
import io.github.vedmider.dylext.domain.port.out.VcsClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Keeps the static index consistent with the live repo before a read: if the repo's on-disk git
 * HEAD has moved past the stored cursor (new commits), sync first so skeletons/spans match what
 * {@code working_changes}/{@code recent_changes} already report. Cheap when unchanged (one HEAD read),
 * best-effort (never fails a read), and disabled by {@code dylext.freshness.on-read=false}.
 */
@Component
public class FreshnessGate {

    private final DigestStore store;
    private final VcsClient vcs;
    private final SyncUseCase sync;
    private final boolean onRead;

    public FreshnessGate(DigestStore store, VcsClient vcs, SyncUseCase sync,
                         @Value("${dylext.freshness.on-read:true}") boolean onRead) {
        this.store = store;
        this.vcs = vcs;
        this.sync = sync;
        this.onRead = onRead;
    }

    /** Ensure the repo's index reflects HEAD; sync on drift. Safe to call before any static read. */
    public void ensureFresh(String repo) {
        if (!onRead || repo == null || repo.isBlank()) {
            return;
        }
        Optional<String> root = store.repoRoot(repo);
        if (root.isEmpty() || !vcs.isRepo(root.get())) {
            return;   // not a git repo (or never indexed) — nothing to reconcile
        }
        String head = vcs.headSha(root.get());
        if (head == null) {
            return;
        }
        if (!head.equals(store.repoCursor(repo).orElse(null))) {
            try {
                sync.sync(repo, root.get());
            } catch (RuntimeException ignored) {
                // Freshness is best-effort; a sync failure must not break the read.
            }
        }
    }
}
