package io.github.vedmider.dylext.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SessionReposTest {

    private static SessionEntry fact(String repo) {
        return SessionEntry.fact(repo, "F.java", "F.m", "edit");
    }

    @Test
    void addsNewReposInFirstSeenOrder() {
        Session s = Session.start("s", "task");

        List<String> repos = s.reposWith(List.of(fact("A"), fact("B")));

        assertThat(repos).containsExactly("A", "B");
    }

    @Test
    void keepsExistingReposAndDoesNotDuplicate() {
        Session s = new Session("s", "task", Session.Status.ACTIVE, List.of("A"),
                List.of(), List.of(), null);

        List<String> repos = s.reposWith(List.of(fact("A"), fact("B"), fact("A")));

        assertThat(repos).containsExactly("A", "B");
    }

    @Test
    void ignoresEntriesWithoutARepo() {
        Session s = Session.start("s", "task");

        List<String> repos = s.reposWith(List.of(
                fact(null), fact(""), fact("  "), SessionEntry.decision("use X", "because")));

        assertThat(repos).isEmpty();
    }

    @Test
    void decisionEntriesCarryingARepoCount() {
        Session s = Session.start("s", "task");
        SessionEntry d = new SessionEntry(SessionEntry.Type.DECISION, "split module", "cohesion",
                "B", null, null, null);

        assertThat(s.reposWith(List.of(d))).containsExactly("B");
    }
}
