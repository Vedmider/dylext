package io.github.vedmider.dylext.domain.port.in;

import io.github.vedmider.dylext.domain.port.out.VcsClient.Commit;
import io.github.vedmider.dylext.domain.port.out.VcsClient.WorkingChange;

import java.util.List;

/** Inbound port for dynamic context: committed git evolution and uncommitted working-tree changes. */
public interface DynamicContextUseCase {

    List<Commit> recentChanges(String repo, String pathFilter, int limit);

    List<WorkingChange> workingChanges(String repo, String pathFilter);
}
