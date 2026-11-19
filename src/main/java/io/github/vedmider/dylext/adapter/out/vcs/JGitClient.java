package io.github.vedmider.dylext.adapter.out.vcs;

import io.github.vedmider.dylext.domain.port.out.VcsClient;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.WorkingTreeIterator;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.NotIgnoredFilter;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JGit-backed dynamic context: commit history and uncommitted working-tree diffs. The indexed root
 * may be any folder inside a work tree (a module of a multi-module repo): the git dir is discovered
 * upward, results are scoped to the root, and returned paths are relative to it.
 */
@Component
public class JGitClient implements VcsClient {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.systemDefault());

    /** An opened repository plus the root's folder inside its work tree ("" or "web/"). */
    private record Located(Repository repo, String prefix) implements AutoCloseable {
        String prefixNoSlash() {
            return prefix.isEmpty() ? "" : prefix.substring(0, prefix.length() - 1);
        }

        /** Repo-relative path -> root-relative path, or null when it lies outside the root. */
        String rel(String repoPath) {
            return repoPath.startsWith(prefix) ? repoPath.substring(prefix.length()) : null;
        }

        TreeFilter scope() {
            return prefix.isEmpty() ? TreeFilter.ALL : PathFilter.create(prefixNoSlash());
        }

        @Override
        public void close() {
            repo.close();
        }
    }

    /** Finds the git dir at or above {@code root}; null when {@code root} is not inside a work tree. */
    private static Located locate(String root) throws IOException {
        File dir = new File(root).getAbsoluteFile();
        FileRepositoryBuilder builder = new FileRepositoryBuilder().findGitDir(dir);
        if (builder.getGitDir() == null) {
            return null;
        }
        File workTree = nearestWithDotGit(dir);
        if (workTree != null) {
            builder.setWorkTree(workTree);
        }
        Repository repo = builder.build();
        if (repo.isBare()) {
            repo.close();
            return null;
        }
        Path wt = real(repo.getWorkTree().toPath());
        Path at = real(dir.toPath());
        String rel = wt.relativize(at).toString().replace(File.separatorChar, '/');
        return new Located(repo, rel.isEmpty() ? "" : rel + "/");
    }

    private static File nearestWithDotGit(File dir) {
        for (File c = dir; c != null; c = c.getParentFile()) {
            if (new File(c, ".git").exists()) {
                return c;
            }
        }
        return null;
    }

    private static Path real(Path p) throws IOException {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    @Override
    public boolean isRepo(String root) {
        try (Located l = locate(root)) {
            return l != null;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public String headSha(String root) {
        try (Located l = locate(root)) {
            if (l == null) {
                return null;
            }
            ObjectId head = l.repo().resolve(Constants.HEAD);
            return head == null ? null : head.getName();
        } catch (Exception e) {
            throw new UncheckedIOException(new IOException("git head on " + root, e));
        }
    }

    @Override
    public String currentBranch(String root) {
        try (Located l = locate(root)) {
            if (l == null) {
                return null;
            }
            String full = l.repo().getFullBranch();
            if (full == null) {
                return null;
            }
            if (full.startsWith(Constants.R_HEADS)) {
                return full.substring(Constants.R_HEADS.length());
            }
            return "(detached " + full.substring(0, Math.min(8, full.length())) + ")";
        } catch (Exception e) {
            throw new UncheckedIOException(new IOException("git branch on " + root, e));
        }
    }

    @Override
    public List<String> changedFilesSince(String root, String oldSha) {
        if (oldSha == null || oldSha.isBlank()) {
            return List.of();
        }
        try (Located l = locate(root)) {
            if (l == null) {
                return List.of();
            }
            Repository repo = l.repo();
            ObjectId oldTreeId = repo.resolve(oldSha + "^{tree}");
            ObjectId headTreeId = repo.resolve(Constants.HEAD + "^{tree}");
            if (oldTreeId == null || headTreeId == null) {
                return List.of();
            }
            try (var reader = repo.newObjectReader();
                 DiffFormatter fmt = new DiffFormatter(new ByteArrayOutputStream())) {
                fmt.setRepository(repo);
                fmt.setPathFilter(l.scope());
                CanonicalTreeParser oldTree = new CanonicalTreeParser();
                oldTree.reset(reader, oldTreeId);
                CanonicalTreeParser newTree = new CanonicalTreeParser();
                newTree.reset(reader, headTreeId);
                List<String> out = new ArrayList<>();
                for (DiffEntry e : fmt.scan(oldTree, newTree)) {
                    String rel = l.rel(pathOf(e));
                    if (rel != null) {
                        out.add(rel);
                    }
                }
                return out;
            }
        } catch (Exception e) {
            throw new UncheckedIOException(new IOException("git diff-since on " + root, e));
        }
    }

    @Override
    public List<Commit> recentCommits(String root, String pathFilter, int limit) {
        try (Located l = locate(root); Git git = l == null ? null : new Git(l.repo())) {
            if (l == null) {
                return List.of();
            }
            var log = git.log().setMaxCount(limit);
            String scoped = (l.prefix() + (pathFilter == null ? "" : pathFilter));
            if (!scoped.isBlank()) {
                log.addPath(scoped);
            }
            List<Commit> out = new ArrayList<>();
            for (RevCommit c : log.call()) {
                out.add(new Commit(
                        c.getName(),
                        c.getAuthorIdent().getName(),
                        TS.format(Instant.ofEpochSecond(c.getCommitTime())),
                        c.getShortMessage(),
                        filesOf(l, c)));
            }
            return out;
        } catch (Exception e) {
            throw new UncheckedIOException(new IOException("git log on " + root, e));
        }
    }

    @Override
    public List<WorkingChange> workingChanges(String root, String pathFilter) {
        try (Located l = locate(root)) {
            if (l == null) {
                return List.of();
            }
            Repository repo = l.repo();
            ObjectId head = repo.resolve(Constants.HEAD + "^{tree}");
            CanonicalTreeParser headTree = new CanonicalTreeParser();
            if (head != null) {
                try (var reader = repo.newObjectReader()) {
                    headTree.reset(reader, head);
                }
            }

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (DiffFormatter fmt = new DiffFormatter(buf)) {
                fmt.setRepository(repo);
                // tree 1 is the working tree: hide gitignored files, stay inside the root
                fmt.setPathFilter(AndTreeFilter.create(l.scope(), new NotIgnoredFilter(1)));
                List<DiffEntry> entries = fmt.scan(
                        head != null ? headTree : new EmptyTreeIterator(), new FileTreeIterator(repo));

                List<WorkingChange> out = new ArrayList<>();
                for (DiffEntry entry : entries) {
                    String path = l.rel(pathOf(entry));
                    if (path == null) {
                        continue;
                    }
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

    @Override
    public Optional<List<String>> listWorkingFiles(String root) {
        try (Located l = locate(root)) {
            if (l == null) {
                return Optional.empty();
            }
            List<String> out = new ArrayList<>();
            try (TreeWalk tw = new TreeWalk(l.repo())) {
                tw.addTree(new FileTreeIterator(l.repo()));
                tw.setRecursive(false);
                if (!l.prefix().isEmpty()) {
                    tw.setFilter(PathFilter.create(l.prefixNoSlash()));
                }
                while (tw.next()) {
                    WorkingTreeIterator wti = tw.getTree(0, WorkingTreeIterator.class);
                    if (wti.isEntryIgnored()) {
                        continue;                       // ignored files and whole ignored directories
                    }
                    if (tw.isSubtree()) {
                        tw.enterSubtree();              // nested repositories are gitlinks: never entered
                        continue;
                    }
                    FileMode mode = tw.getFileMode(0);
                    if (mode == FileMode.REGULAR_FILE || mode == FileMode.EXECUTABLE_FILE) {
                        String rel = l.rel(tw.getPathString());
                        if (rel != null) {
                            out.add(rel);
                        }
                    }
                }
            }
            return Optional.of(out);
        } catch (Exception e) {
            throw new UncheckedIOException(new IOException("git ls on " + root, e));
        }
    }

    private static String pathOf(DiffEntry e) {
        return e.getChangeType() == DiffEntry.ChangeType.DELETE ? e.getOldPath() : e.getNewPath();
    }

    private static List<String> filesOf(Located l, RevCommit c) throws IOException {
        if (c.getParentCount() == 0) {
            return List.of();
        }
        try (DiffFormatter fmt = new DiffFormatter(new ByteArrayOutputStream())) {
            fmt.setRepository(l.repo());
            fmt.setPathFilter(l.scope());
            List<String> files = new ArrayList<>();
            for (DiffEntry e : fmt.scan(c.getParent(0).getTree(), c.getTree())) {
                String rel = l.rel(pathOf(e));
                if (rel != null) {
                    files.add(rel);
                }
            }
            return files;
        }
    }
}
