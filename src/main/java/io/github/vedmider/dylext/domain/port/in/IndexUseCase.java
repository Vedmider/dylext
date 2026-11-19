package io.github.vedmider.dylext.domain.port.in;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Inbound port: index a repository's source into static context (symbols + skeletons). */
public interface IndexUseCase {

    IndexResult index(String repo, String root);

    /** Index/refresh a single file (used by sync). @return symbols written (0 if skipped/unchanged). */
    int indexFile(String repo, String root, String relativePath);

    /**
     * @param filesFailed        files that could not be read (binary/unreadable) or crashed their parser
     * @param filesDegraded      indexed, but only as a MODULE-only stub because the parser could not parse them
     * @param skippedUnsupported source files ignored for lack of a parser, by language (or extension)
     * @param parserUnreachable  source files skipped because their configured parser sidecar was unreachable, by language
     * @param problems           a sample of failed/degraded files with the reason
     * @param replacedRoot       the previous root, when this repo name was re-pointed to a different folder
     */
    record IndexResult(String repo, int filesScanned, int filesIndexed, int filesSkipped, int symbols,
                       int filesFailed, int filesDegraded,
                       @JsonInclude(JsonInclude.Include.NON_EMPTY) java.util.Map<String, Integer> skippedUnsupported,
                       @JsonInclude(JsonInclude.Include.NON_EMPTY) java.util.Map<String, Integer> parserUnreachable,
                       @JsonInclude(JsonInclude.Include.NON_EMPTY) java.util.List<String> problems,
                       @JsonInclude(JsonInclude.Include.NON_NULL) String branch,
                       @JsonInclude(JsonInclude.Include.NON_NULL) String head,
                       @JsonInclude(JsonInclude.Include.NON_NULL) String replacedRoot) {

        public IndexResult(String repo, int filesScanned, int filesIndexed, int filesSkipped, int symbols) {
            this(repo, filesScanned, filesIndexed, filesSkipped, symbols, 0, 0,
                    java.util.Map.of(), java.util.Map.of(), java.util.List.of(), null, null, null);
        }
    }
}
