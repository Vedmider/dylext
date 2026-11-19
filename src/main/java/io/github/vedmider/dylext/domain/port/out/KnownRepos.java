package io.github.vedmider.dylext.domain.port.out;

/** Read-only view of which repo names have been indexed (lets sessions flag typos). */
public interface KnownRepos {

    boolean isKnown(String repo);
}
