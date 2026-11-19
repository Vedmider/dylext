package io.github.vedmider.dylext.domain.model;

/**
 * The (provider, repo, commit) an answer was computed against — the cache key and freshness tag.
 * {@code dirty} marks a live working-tree read (never cacheable).
 */
public record EpochRef(String provider, String repo, String commit, boolean dirty) {

    public static final String PROVIDER = "dylext";
    public static final String UNINDEXED = "unindexed";

    public static EpochRef committed(String repo, String commit) {
        return new EpochRef(PROVIDER, repo, commit == null ? UNINDEXED : commit, false);
    }

    public EpochRef asDirty() {
        return new EpochRef(provider, repo, commit, true);
    }

    public String token() {
        return provider + ":" + repo + "@" + (dirty ? "dirty" : commit);
    }
}
