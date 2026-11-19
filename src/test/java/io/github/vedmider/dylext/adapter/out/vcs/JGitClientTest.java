package io.github.vedmider.dylext.adapter.out.vcs;

import io.github.vedmider.dylext.domain.port.out.VcsClient.Commit;
import io.github.vedmider.dylext.domain.port.out.VcsClient.WorkingChange;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Real-git behaviour of the VCS adapter, incl. a module folder inside a larger repository. */
class JGitClientTest {

    private final JGitClient vcs = new JGitClient();

    @TempDir
    Path dir;

    private Git init() throws Exception {
        return Git.init().setDirectory(dir.toFile()).call();
    }

    private void write(String rel, String content) throws IOException {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private RevCommit commit(Git g, String msg) throws Exception {
        g.add().addFilepattern(".").call();
        return g.commit().setMessage(msg).setAuthor("T", "t@e.com").setCommitter("T", "t@e.com")
                .setSign(false).call();
    }

    private String web() {
        return dir.resolve("web").toString();
    }

    @Test
    void aSubfolderOfARepoIsARepoAndSharesItsHead() throws Exception {
        try (Git g = init()) {
            write("web/F.java", "a");
            write("core/G.java", "b");
            RevCommit c = commit(g, "init");

            assertThat(vcs.isRepo(web())).isTrue();
            assertThat(vcs.headSha(web())).isEqualTo(c.getName());
        }
    }

    @Test
    void aPlainDirectoryIsNotARepo(@TempDir Path plain) {
        assertThat(vcs.isRepo(plain.toString())).isFalse();
        assertThat(vcs.workingChanges(plain.toString(), null)).isEmpty();
        assertThat(vcs.listWorkingFiles(plain.toString())).isEmpty();
        assertThat(vcs.currentBranch(plain.toString())).isNull();
    }

    @Test
    void workingChangesInASubfolderAreScopedToItAndModuleRelative() throws Exception {
        try (Git g = init()) {
            write("web/F.java", "a");
            write("core/G.java", "b");
            commit(g, "init");
            write("web/F.java", "a2");
            write("core/G.java", "b2");
            write("web/New.java", "n");

            List<WorkingChange> changes = vcs.workingChanges(web(), null);

            assertThat(changes).extracting(WorkingChange::path).containsExactlyInAnyOrder("F.java", "New.java");
            assertThat(vcs.workingChanges(web(), "New")).extracting(WorkingChange::path).containsExactly("New.java");
        }
    }

    @Test
    void workingChangesAtTheRepoRootKeepRepoRelativePaths() throws Exception {
        try (Git g = init()) {
            write("web/F.java", "a");
            commit(g, "init");
            write("web/F.java", "a2");

            assertThat(vcs.workingChanges(dir.toString(), null)).extracting(WorkingChange::path)
                    .containsExactly("web/F.java");
        }
    }

    @Test
    void gitignoredFilesAreNotReportedAsWorkingChanges() throws Exception {
        try (Git g = init()) {
            write(".gitignore", "build/\n");
            write("web/F.java", "a");
            commit(g, "init");
            write("web/build/Gen.java", "generated");
            write("web/Real.java", "real");

            assertThat(vcs.workingChanges(web(), null)).extracting(WorkingChange::path).containsExactly("Real.java");
        }
    }

    @Test
    void changedFilesSinceInASubfolderAreScopedAndModuleRelative() throws Exception {
        try (Git g = init()) {
            write("web/F.java", "a");
            write("core/G.java", "b");
            RevCommit c1 = commit(g, "init");
            write("web/F.java", "a2");
            write("core/G.java", "b2");
            commit(g, "both");

            assertThat(vcs.changedFilesSince(web(), c1.getName())).containsExactly("F.java");
            assertThat(vcs.changedFilesSince(dir.toString(), c1.getName()))
                    .containsExactlyInAnyOrder("web/F.java", "core/G.java");
        }
    }

    @Test
    void recentCommitsInASubfolderOnlyListCommitsTouchingIt() throws Exception {
        try (Git g = init()) {
            write("web/F.java", "a");
            write("core/G.java", "b");
            commit(g, "init");
            write("core/G.java", "b2");
            commit(g, "core only");
            write("web/F.java", "a2");
            commit(g, "web change");

            List<Commit> commits = vcs.recentCommits(web(), null, 10);

            assertThat(commits).extracting(Commit::message).containsExactly("web change", "init");
            assertThat(commits.get(0).files()).containsExactly("F.java");
        }
    }

    @Test
    void listWorkingFilesSkipsIgnoredAndNestedRepositoriesButKeepsUntracked() throws Exception {
        try (Git g = init()) {
            write(".gitignore", "build/\n*.log\n");
            write("src/A.java", "a");
            write("web/C.java", "c");
            commit(g, "init");
            write("build/gen/B.java", "ignored dir");
            write("x.log", "ignored file");
            write("new/D.java", "untracked, not ignored");
            Path nested = dir.resolve("vendor/lib");
            Files.createDirectories(nested);
            try (Git ng = Git.init().setDirectory(nested.toFile()).call()) {
                Files.writeString(nested.resolve("N.java"), "nested repo");
            }

            List<String> files = vcs.listWorkingFiles(dir.toString()).orElseThrow();

            assertThat(files).containsExactlyInAnyOrder(".gitignore", "src/A.java", "web/C.java", "new/D.java");
            assertThat(vcs.listWorkingFiles(web()).orElseThrow()).containsExactly("C.java");
        }
    }

    @Test
    void aLinkedWorktreeIsARepoWithItsOwnWorkingChanges() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(gitCliAvailable(), "git CLI not available");
        try (Git g = init()) {
            write("F.java", "a");
            commit(g, "init");
            Path wt = dir.resolveSibling(dir.getFileName() + "-wt");
            Process p = new ProcessBuilder("git", "-C", dir.toString(), "worktree", "add", "-q", "-b", "wtb", wt.toString())
                    .redirectErrorStream(true).start();
            assertThat(p.waitFor()).as(new String(p.getInputStream().readAllBytes())).isZero();
            Files.writeString(wt.resolve("F.java"), "changed in the worktree");

            assertThat(vcs.isRepo(wt.toString())).isTrue();
            assertThat(vcs.workingChanges(wt.toString(), null)).extracting(WorkingChange::path).containsExactly("F.java");
            assertThat(vcs.currentBranch(wt.toString())).isEqualTo("wtb");
        }
    }

    private static boolean gitCliAvailable() {
        try {
            return new ProcessBuilder("git", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void currentBranchIsTheBranchNameOrDetached() throws Exception {
        try (Git g = init()) {
            write("F.java", "a");
            RevCommit c1 = commit(g, "init");
            String branch = g.getRepository().getBranch();
            write("F.java", "a2");
            commit(g, "second");

            assertThat(vcs.currentBranch(dir.toString())).isEqualTo(branch);

            g.checkout().setName(c1.getName()).call();
            assertThat(vcs.currentBranch(dir.toString())).startsWith("(detached");
        }
    }
}
