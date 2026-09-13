package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * <b>C'est Chrome qui télécharge</b> (F-108 / SF-108-03, cadrage §2 et §5).
 *
 * <p>La garde de SF-87-02 est conservée telle quelle : <b>aucune adresse signée ne passe par notre
 * code</b>. Le runner dirige les téléchargements du navigateur vers un dossier <b>fixe</b> du volet
 * ({@code Browser.setDownloadBehavior}), puis navigue vers l'adresse de téléchargement de l'élément —
 * une adresse <b>construite par nous et non signée</b>, que la session du navigateur autorise. Les
 * octets vont de Microsoft à Chrome, puis au disque ; ils ne transitent jamais par la liaison.</p>
 *
 * <p>Ce qui est vérifié, parce que « le fichier est là » ne suffit pas :</p>
 * <ul>
 *   <li><b>le démarrage</b> — rien dans le dossier au délai : le téléchargement est bloqué ou
 *       refusé, et c'est un manque nommé ;</li>
 *   <li><b>la taille</b> — celle que la bibliothèque annonce ;</li>
 *   <li><b>la nature</b> — une page HTML reçue à la place d'un document est une page d'erreur ou
 *       d'accès refusé : elle est supprimée, jamais présentée comme le document.</li>
 * </ul>
 *
 * <p>Dès que le fichier a démarré, le comportement de téléchargement est <b>remis par défaut</b> : les
 * téléchargements de l'utilisateur ne doivent pas atterrir dans le dossier du volet.</p>
 */
public final class ChromeDownloads {

    static final long START_WAIT_MS = 20_000L;
    static final long STEP_MS = 500L;

    private final BrowserLink.Sleeper sleeper;

    public ChromeDownloads(BrowserLink.Sleeper sleeper) {
        this.sleeper = sleeper;
    }

    /** Où en est un téléchargement. */
    public enum State { DONE, IN_PROGRESS, BLOCKED, NONE }

    /** L'issue d'un téléchargement : l'état, le fichier, et le manque s'il y en a un. */
    public record Outcome(State state, Path file, long bytes, TeamsGap gap) {

        static Outcome none() {
            return new Outcome(State.NONE, null, 0L, null);
        }

        public boolean done() {
            return state == State.DONE;
        }
    }

    /**
     * Lance le téléchargement par Chrome et attend au plus {@code waitMs} qu'il se termine.
     *
     * @param dir           dossier <b>fixe</b> du volet, jamais un paramètre d'appel
     * @param expectedName  nom sous lequel ranger le fichier
     * @param expectedBytes taille annoncée par la bibliothèque, ou {@code -1}
     */
    public Outcome fetch(PageActions actions, SharePointLocation file, Path dir, String expectedName,
            long expectedBytes, long waitMs) {
        String name = safeName(expectedName);
        try {
            Files.createDirectories(dir);
            clear(dir);
        } catch (IOException e) {
            return new Outcome(State.BLOCKED, null, 0L, TeamsGap.of(TeamsGapKind.DOWNLOAD_BLOCKED,
                    file.label(), "dossier du volet non inscriptible : " + dir));
        }
        actions.setDownloadDirectory(dir.toString());
        boolean started = false;
        try {
            actions.navigate(file.downloadUrl());
            long waited = 0L;
            while (waited < START_WAIT_MS) {
                if (!listFiles(dir).isEmpty()) {
                    started = true;
                    break;
                }
                sleep();
                waited += STEP_MS;
            }
        } finally {
            actions.resetDownloads();
        }
        if (!started) {
            return new Outcome(State.BLOCKED, null, 0L, TeamsGap.of(TeamsGapKind.DOWNLOAD_BLOCKED,
                    file.label(), "le téléchargement n'a pas démarré dans le délai : bloqué par "
                            + "l'organisateur ou la politique du tenant, ou refusé"));
        }
        long waited = 0L;
        while (true) {
            Outcome outcome = check(dir, name, expectedBytes, file.label());
            if (outcome.state() != State.IN_PROGRESS || waited >= waitMs) {
                return outcome;
            }
            sleep();
            waited += STEP_MS;
        }
    }

    /**
     * L'état du dossier, sans aucun geste : un rappel après « en cours » passe par ici. Un fichier
     * complet est renommé au nom attendu.
     */
    public Outcome check(Path dir, String expectedName, long expectedBytes, String where) {
        String name = safeName(expectedName);
        Path target = dir.resolve(name);
        List<Path> files = listFiles(dir);
        if (files.isEmpty()) {
            return Outcome.none();
        }
        if (Files.exists(target) && files.size() == 1) {
            return verify(target, expectedBytes, where);
        }
        Optional<Path> partial = files.stream().filter(ChromeDownloads::isPartial).findFirst();
        if (partial.isPresent()) {
            return new Outcome(State.IN_PROGRESS, partial.get(), sizeOf(partial.get()), null);
        }
        Path candidate = files.stream().filter(path -> !path.equals(target)).findFirst()
                .orElse(target);
        long bytes = sizeOf(candidate);
        if (expectedBytes >= 0 && bytes < expectedBytes && !looksLikeHtml(candidate, name)) {
            return new Outcome(State.IN_PROGRESS, candidate, bytes, null);
        }
        Outcome verified = verify(candidate, expectedBytes, where);
        if (!verified.done()) {
            return verified;
        }
        try {
            if (!candidate.equals(target)) {
                Files.move(candidate, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return new Outcome(State.DONE, target, bytes, null);
        } catch (IOException e) {
            return new Outcome(State.DONE, candidate, bytes, null);
        }
    }

    private static Outcome verify(Path file, long expectedBytes, String where) {
        long bytes = sizeOf(file);
        if (looksLikeHtml(file, file.getFileName().toString())) {
            delete(file);
            return new Outcome(State.BLOCKED, null, 0L, TeamsGap.of(TeamsGapKind.DOWNLOAD_BLOCKED,
                    where, "une page HTML a été reçue à la place du fichier (accès refusé ou "
                            + "téléchargement bloqué) : elle a été supprimée"));
        }
        if (expectedBytes >= 0 && bytes != expectedBytes) {
            delete(file);
            return new Outcome(State.BLOCKED, null, 0L, TeamsGap.of(TeamsGapKind.SHAPE_MISMATCH,
                    where, "taille reçue " + bytes + " octets, annoncée " + expectedBytes
                            + " : le fichier a été écarté"));
        }
        return new Outcome(State.DONE, file, bytes, null);
    }

    /** Un nom de fichier, jamais un chemin : séparateurs et caractères réservés remplacés. */
    static String safeName(String raw) {
        String value = raw == null ? "" : raw.strip();
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < value.length() && out.length() < 200; index++) {
            char c = value.charAt(index);
            out.append(c < 32 || "\\/:*?\"<>|".indexOf(c) >= 0 ? '_' : c);
        }
        String name = out.toString();
        while (name.startsWith(".")) {
            name = name.substring(1);
        }
        return name.isBlank() ? "fichier" : name;
    }

    private static boolean isPartial(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".crdownload") || name.endsWith(".tmp") || name.endsWith(".partial");
    }

    private static boolean looksLikeHtml(Path file, String expectedName) {
        String lower = expectedName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".aspx")) {
            return false;
        }
        try (InputStream in = Files.newInputStream(file)) {
            String head = new String(in.readNBytes(512), StandardCharsets.UTF_8).strip()
                    .toLowerCase(Locale.ROOT);
            return head.startsWith("<!doctype html") || head.startsWith("<html");
        } catch (IOException e) {
            return false;
        }
    }

    private static List<Path> listFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Vide le dossier fixe d'un téléchargement : un reste d'essai précédent fausserait l'attente. */
    private static void clear(Path dir) {
        listFiles(dir).forEach(ChromeDownloads::delete);
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // un fichier qu'on ne peut pas supprimer reste là ; il n'est de toute façon pas rendu
        }
    }

    private void sleep() {
        if (sleeper != null) {
            sleeper.sleep(STEP_MS);
        }
    }
}
