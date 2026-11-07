package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.domain.port.in.IndexUseCase;
import io.github.vedmider.dylext.domain.port.in.SyncUseCase;
import io.github.vedmider.dylext.domain.port.out.DigestStore;
import io.github.vedmider.dylext.domain.port.out.VcsClient;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Incremental sync: first run (no cursor) does a full index; later runs re-index only the files
 * git reports changed since the stored cursor, then advance the cursor to HEAD.
 */
@Service
public class SyncService implements SyncUseCase {

    private final IndexUseCase index;
    private final DigestStore store;
    private final VcsClient vcs;

    public SyncService(IndexUseCase index, DigestStore store, VcsClient vcs) {
        this.index = index;
        this.store = store;
        this.vcs = vcs;
    }

    @Override
    public SyncResult sync(String repo, String root) {
        String head = vcs.headSha(root);
        Optional<String> cursor = store.repoCursor(repo);

        if (!vcs.isRepo(root) || cursor.isEmpty()) {
            int changed = index.index(repo, root).filesIndexed();
            if (head != null) {
                store.saveRepoCursor(repo, head);
            }
            return new SyncResult(repo, null, head, changed, true);
        }

        List<String> changed = vcs.changedFilesSince(root, cursor.get()).stream()
                .filter(p -> p.endsWith(".java"))
                .toList();
        for (String rel : changed) {
            if (Files.exists(Path.of(root).resolve(rel))) {
                index.indexFile(repo, root, rel);
            } else {
                store.deleteFile(repo, rel);
            }
        }
        if (head != null) {
            store.saveRepoCursor(repo, head);
        }
        return new SyncResult(repo, cursor.get(), head, changed.size(), false);
    }
}
