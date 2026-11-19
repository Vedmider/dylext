package io.github.vedmider.dylext.application;

import java.nio.file.Files;
import java.nio.file.Path;

/** Validates a repo root supplied by a client. Failures are caller mistakes (mapped to -32602). */
final class RootPaths {

    private RootPaths() {
    }

    static Path requireDirectory(String root) {
        if (root == null || root.isBlank()) {
            throw new IllegalArgumentException("root is required (an absolute path to the repo or module folder)");
        }
        Path p = Path.of(root);
        if (!p.isAbsolute()) {
            throw new IllegalArgumentException(
                    "root must be an absolute path (the server's working directory is unknown to clients): " + root);
        }
        if (!Files.exists(p)) {
            throw new IllegalArgumentException("root does not exist: " + root);
        }
        if (!Files.isDirectory(p)) {
            throw new IllegalArgumentException("root is not a directory: " + root);
        }
        return p.normalize();
    }
}
