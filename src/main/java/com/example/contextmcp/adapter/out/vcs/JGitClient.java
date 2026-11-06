package com.example.contextmcp.adapter.out.vcs;

import com.example.contextmcp.domain.port.out.VcsClient;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** JGit-backed dynamic context: commit history and uncommitted working-tree diffs. */
@Component
public class JGitClient implements VcsClient {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.systemDefault());

    @Override
    public boolean isRepo(String root) {
        return Path.of(root, ".git").toFile().exists();
    }

    @Override
    public String headSha(String root) {
        if (!isRepo(root)) {
            return null;
        }
        try (Git git = open(root)) {
            ObjectId head = git.getRepository().resolve(Constants.HEAD);
            return head == null ? null : head.getName();
        } catch (Exception e) {
            throw new UncheckedIOException(new IOException("git head on " + root, e));
        }
    }

    @Override
    public List<String> changedFilesSince(String root, String oldSha) {
        if (!isRepo(root) || oldSha == null || oldSha.isBlank()) {
            return List.of();
        }
        try (Git git = open(root)) {
            Repository repo = git.getRepository();
            ObjectId oldCommit = repo.resolve(oldSha + "^{tree}");
            ObjectId head = repo.resolve(Constants.HEAD + "^{tree}");
            if (oldCommit == null || head == null) {
                return List.of();
            }
            try (var reader = repo.newObjectReader();
                 DiffFormatter fmt = new DiffFormatter(new ByteArrayOutputStream())) {
                fmt.setRepository(repo);
                CanonicalTreeParser oldTree = new CanonicalTreeParser();
                oldTree.reset(reader, oldCommit);
                CanonicalTreeParser newTree = new CanonicalTreeParser();
                newTree.reset(reader, head);
                List<String> out = new ArrayList<>();
                for (DiffEntry e : fmt.scan(oldTree, newTree)) {
                    out.add(e.getChangeType() == DiffEntry.ChangeType.DELETE
                            ? e.getOldPath() : e.getNewPath());
                }
                return out;
            }
        } catch (Exception e) {
            throw new UncheckedIOException(new IOException("git diff-since on " + root, e));
        }
    }

    @Override
    public List<Commit> recentCommits(String root, String pathFilter, int limit) {
        if (!isRepo(root)) {
            return List.of();
        }
        try (Git git = open(root)) {
            var log = git.log().setMaxCount(limit);
            if (pathFilter != null && !pathFilter.isBlank()) {
                log.addPath(pathFilter);
            }
            List<Commit> out = new ArrayList<>();
            for (RevCommit c : log.call()) {
                out.add(new Commit(
                        c.getName(),
                        c.getAuthorIdent().getName(),
                        TS.format(Instant.ofEpochSecond(c.getCommitTime())),
                        c.getShortMessage(),
                        filesOf(git, c)));
            }
            return out;
        } catch (Exception e) {
            throw new UncheckedIOException(new IOException("git log on " + root, e));
        }
    }

    @Override
    public List<WorkingChange> workingChanges(String root, String pathFilter) {
        if (!isRepo(root)) {
            return List.of();
        }
        try (Git git = open(root)) {
            Repository repo = git.getRepository();
            ObjectId head = repo.resolve(Constants.HEAD + "^{tree}");
            CanonicalTreeParser headTree = new CanonicalTreeParser();
            if (head != null) {
                try (var reader = repo.newObjectReader()) {
                    headTree.reset(reader, head);
                }
            }

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            List<DiffEntry> entries;
            try (DiffFormatter fmt = new DiffFormatter(buf)) {
                fmt.setRepository(repo);
                entries = fmt.scan(head != null ? headTree : new org.eclipse.jgit.treewalk.EmptyTreeIterator(),
                        new FileTreeIterator(repo));

                List<WorkingChange> out = new ArrayList<>();
                for (DiffEntry entry : entries) {
                    String path = entry.getChangeType() == DiffEntry.ChangeType.DELETE
                            ? entry.getOldPath() : entry.getNewPath();
                    if (pathFilter != null && !pathFilter.isBlank() && !path.startsWith(pathFilter)) {
                        continue;
                    }
                    buf.reset();
                    fmt.format(entry);
                    fmt.flush();
                    out.add(new WorkingChange(path, entry.getChangeType().name(),
                            buf.toString(StandardCharsets.UTF_8)));
                }
                return out;
            }
        } catch (Exception e) {
            throw new UncheckedIOException(new IOException("git diff on " + root, e));
        }
    }

    private static Git open(String root) throws IOException {
        Repository repo = new FileRepositoryBuilder()
                .setGitDir(Path.of(root, ".git").toFile())
                .readEnvironment()
                .build();
        return new Git(repo);
    }

    private static List<String> filesOf(Git git, RevCommit c) throws IOException {
        if (c.getParentCount() == 0) {
            return List.of();
        }
        try (DiffFormatter fmt = new DiffFormatter(new ByteArrayOutputStream())) {
            fmt.setRepository(git.getRepository());
            List<String> files = new ArrayList<>();
            for (DiffEntry e : fmt.scan(c.getParent(0).getTree(), c.getTree())) {
                files.add(e.getChangeType() == DiffEntry.ChangeType.DELETE ? e.getOldPath() : e.getNewPath());
            }
            return files;
        }
    }
}
