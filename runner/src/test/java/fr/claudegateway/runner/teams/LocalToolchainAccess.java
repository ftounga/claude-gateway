package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.nio.file.Path;

/** Accès de test au rapatriement réel de {@link LocalToolchain} (F-190 / SF-190-06). */
public final class LocalToolchainAccess {

    private LocalToolchainAccess() {
    }

    public static long download(String url, Path into) throws IOException {
        return LocalToolchain.httpDownloader().fetch(url, into);
    }
}
