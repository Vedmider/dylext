package io.github.vedmider.dylext.domain.port.in;

/** Inbound port: index a repository's source into static context (symbols + skeletons). */
public interface IndexUseCase {

    IndexResult index(String repo, String root);

    /** Index/refresh a single file (used by sync). @return symbols written (0 if skipped/unchanged). */
    int indexFile(String repo, String root, String relativePath);

    record IndexResult(String repo, int filesScanned, int filesIndexed, int filesSkipped,
                       int symbols) {
    }
}
