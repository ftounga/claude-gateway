package fr.claudegateway.runner;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Outils fichiers exécutés <b>sur la machine de l'utilisateur</b> (F-38 / SF-38-04) :
 * {@code list_files}, {@code read_file}, {@code write_file}, {@code search_files}.
 *
 * <p><b>Aucun confinement depuis F-73 / SF-73-01.</b> Un chemin adressé est résolu par
 * {@link PathResolver} — relatif au dossier du projet, ou absolu, ou {@code ~/…} — et <b>aucun
 * emplacement n'est refusé</b>. Ce qui limite ces outils est ce que le compte qui a lancé le runner
 * peut lui-même lire et écrire, plus les bornes de taille du contrat. La garde qui existait ici ne
 * tenait que sur ces quatre outils : {@code bash} n'a jamais rien rencontré, et promettre à moitié
 * revenait à ne rien promettre.</p>
 *
 * <p>Les formats de sortie reproduisent <b>exactement</b> ceux du mode hébergé (une ligne par chemin
 * pour la liste, {@code chemin:ligne: texte} pour la recherche) afin que le prompt du modèle ne
 * dérive pas selon la cible d'exécution.</p>
 *
 * <p>Les {@link ExclusionRules} ne s'appliquent plus qu'au <b>balayage</b> de {@code list_files} et
 * {@code search_files} : bruit de construction et {@code .runnerignore} élaguent la liste pour
 * qu'elle reste lisible. Un chemin <b>nommé</b> est lu ou écrit quoi qu'en disent ces règles.</p>
 *
 * <p>Aucune exécution de commande ici : {@code bash} est porté par {@link BashTool} depuis SF-38-07,
 * et l'aiguillage entre les deux par {@link ToolRouter}.</p>
 */
public final class FileTools implements ToolExecutor {

    /** Borne du champ {@code content} d'un {@code tool_result} (contrat §5). */
    public static final int MAX_CONTENT_BYTES = 524_288;

    /** Au-delà, une lecture tronquée n'a aucune valeur : refus {@code too_large}. */
    static final long MAX_READ_BYTES = 8L * 1024 * 1024;

    /** Fichiers plus gros ignorés par la recherche (balayage utilisable sur un dépôt réel). */
    static final long SEARCH_MAX_FILE_BYTES = 1024L * 1024;

    /** Borne du résultat de recherche, identique au mode hébergé. */
    static final int SEARCH_MAX_CHARS = 8_000;

    /** Nombre maximal d'entrées renvoyées par {@code list_files}. */
    static final int LIST_MAX_ENTRIES = 20_000;

    private static final int BINARY_SNIFF_BYTES = 8_192;

    private final PathResolver paths;

    public FileTools(PathResolver paths) {
        this.paths = paths;
    }

    /**
     * Exécute un outil. Ne lève jamais : toute erreur est convertie en {@link ToolOutcome} porteur
     * d'un code de la liste close du contrat (§4).
     *
     * @param tool    nom d'outil tel qu'exposé au modèle (identité, sans préfixe)
     * @param input   objet d'entrée, éventuellement {@code null}
     * @param context contexte d'appel ; les outils fichiers rendent leur résultat d'un bloc et ne
     *                diffusent rien (seul {@code bash} alimente le flux, contrat §2.3)
     */
    @Override
    public ToolOutcome execute(String tool, JsonNode input, ToolContext context) {
        try {
            return switch (tool) {
                case "list_files" -> listFiles();
                case "read_file" -> readFile(requiredText(input, "path"));
                case "write_file" -> writeFile(requiredText(input, "path"), requiredContent(input));
                case "search_files" -> searchFiles(requiredText(input, "query"));
                default -> ToolOutcome.error("unsupported_tool",
                        "Outil non supporté par ce runner : " + tool);
            };
        } catch (ToolException e) {
            return ToolOutcome.error(e);
        } catch (IOException e) {
            // Message volontairement générique : ni chemin absolu, ni détail système.
            return ToolOutcome.error("io_error", "Erreur d'accès au système de fichiers.");
        }
    }

    /** Chemins des fichiers réguliers sous le dossier du projet, triés, un par ligne. */
    ToolOutcome listFiles() throws IOException {
        List<String> paths = walkFiles();
        boolean truncated = paths.size() >= LIST_MAX_ENTRIES;
        Truncation body = truncate(String.join("\n", paths), MAX_CONTENT_BYTES);
        return ToolOutcome.ok(body.text(), truncated || body.truncated(), -1);
    }

    /** Contenu texte UTF-8 d'un fichier, où qu'il soit sur la machine (F-73). */
    ToolOutcome readFile(String rawPath) throws IOException {
        PathResolver.Resolved resolved = paths.resolve(rawPath);
        Path path = resolved.path();
        requireExistingFile(resolved);
        long size = Files.size(path);
        if (size > MAX_READ_BYTES) {
            throw new ToolException("too_large", "Fichier trop volumineux : " + resolved.display());
        }
        byte[] bytes = readBytes(path, resolved.display());
        Truncation body = truncate(new String(bytes, StandardCharsets.UTF_8), MAX_CONTENT_BYTES);
        return ToolOutcome.ok(body.text(), body.truncated(), bytes.length);
    }

    /** Écrit (ou remplace) un fichier, en créant les dossiers parents manquants (F-73). */
    ToolOutcome writeFile(String rawPath, String content) throws IOException {
        PathResolver.Resolved resolved = paths.resolve(rawPath);
        Path path = resolved.path();
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_CONTENT_BYTES) {
            throw new ToolException("invalid_input",
                    "Contenu trop volumineux (512 Kio au plus) : " + resolved.display());
        }
        if (Files.isDirectory(path)) {
            throw new ToolException("is_directory", "Le chemin est un dossier : " + resolved.display());
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path)) {
            throw new ToolException("not_a_file", "Le chemin n'est pas un fichier : " + resolved.display());
        }
        Path parent = path.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new ToolException("io_error", "Écriture impossible : " + resolved.display());
        }
        return ToolOutcome.ok("Fichier écrit : " + resolved.display(), false, bytes.length);
    }

    /** Recherche de sous-chaîne insensible à la casse ; format {@code chemin:ligne: texte}. */
    ToolOutcome searchFiles(String rawQuery) throws IOException {
        String query = rawQuery.strip();
        if (query.isEmpty()) {
            throw new ToolException("invalid_input", "Paramètre requis manquant : query");
        }
        if (query.length() > 1024) {
            throw new ToolException("invalid_input", "Recherche trop longue (1024 caractères au plus).");
        }
        String needle = query.toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder();
        for (String relative : walkFiles()) {
            checkNotInterrupted();
            String content = readSearchable(paths.root().resolve(relative));
            if (content == null) {
                continue;
            }
            int line = 0;
            for (String text : content.split("\n", -1)) {
                line++;
                if (text.toLowerCase(Locale.ROOT).contains(needle)) {
                    result.append(relative).append(':').append(line).append(": ")
                            .append(text.strip()).append('\n');
                    if (result.length() > SEARCH_MAX_CHARS) {
                        return ToolOutcome.ok(result.append("… (résultats tronqués)").toString(), true, -1);
                    }
                }
            }
        }
        return result.isEmpty() ? ToolOutcome.ok("Aucun résultat.") : ToolOutcome.ok(result.toString());
    }

    /**
     * Balayage du dossier du projet, <b>sans suivre les liens symboliques</b> : un lien n'est pas un
     * fichier régulier, il ne figure donc ni dans la liste ni dans la recherche — ce qui ferme les
     * boucles de liens et garde le listage fini. Un dossier illisible est ignoré.
     *
     * <p>Le balayage part du dossier du projet et ne remonte pas : ce n'est pas un confinement mais
     * un <b>choix de contenu</b> — lister la machine entière n'a aucun sens. Un fichier situé
     * ailleurs se lit très bien en le <b>nommant</b> ({@code read_file}).</p>
     *
     * <p>Les exclusions sont appliquées <b>pendant</b> le balayage : un dossier exclu est élagué
     * ({@code SKIP_SUBTREE}), son contenu n'est donc ni listé ni ouvert — mais il reste lisible si
     * on le nomme.</p>
     */
    private List<String> walkFiles() throws IOException {
        ExclusionRules exclusions = paths.exclusions();
        List<String> found = new ArrayList<>();
        Files.walkFileTree(paths.root(), EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (Thread.currentThread().isInterrupted()) {
                            return FileVisitResult.TERMINATE;
                        }
                        String relative = paths.relativize(dir);
                        if (!relative.isEmpty() && exclusions.isExcludedDirectory(relative)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (Thread.currentThread().isInterrupted()) {
                            return FileVisitResult.TERMINATE;
                        }
                        if (attrs.isRegularFile()) {
                            String relative = paths.relativize(file);
                            if (exclusions.isExcludedFile(relative)) {
                                return FileVisitResult.CONTINUE;
                            }
                            found.add(relative);
                            if (found.size() >= LIST_MAX_ENTRIES) {
                                return FileVisitResult.TERMINATE;
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE; // fichier/dossier illisible : ignoré
                    }
                });
        checkNotInterrupted();
        Collections.sort(found);
        return found;
    }

    /** Contenu texte d'un fichier candidat à la recherche, ou {@code null} s'il est à ignorer. */
    private String readSearchable(Path path) {
        try {
            if (Files.size(path) > SEARCH_MAX_FILE_BYTES) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(path);
            return isBinary(bytes) ? null : new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null; // fichier disparu ou illisible en cours de balayage : ignoré
        }
    }

    private void requireExistingFile(PathResolver.Resolved resolved) {
        Path path = resolved.path();
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new ToolException("not_found", "Fichier introuvable : " + resolved.display());
        }
        if (Files.isDirectory(path)) {
            throw new ToolException("is_directory", "Le chemin est un dossier : " + resolved.display());
        }
        if (!Files.isRegularFile(path)) {
            throw new ToolException("not_a_file", "Le chemin n'est pas un fichier : " + resolved.display());
        }
    }

    private byte[] readBytes(Path path, String display) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            // Droits refusés par l'OS, par exemple : c'est la seule limite qui reste, et elle vient
            // de la machine, pas du runner.
            throw new ToolException("io_error", "Lecture impossible : " + display);
        }
    }

    private static void checkNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new ToolException("cancelled", "Appel interrompu.");
        }
    }

    /** Heuristique binaire : un octet nul dans l'en-tête suffit à écarter le fichier. */
    private static boolean isBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, BINARY_SNIFF_BYTES);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    /** Coupe sur une frontière de caractère (contrat §5) : jamais d'UTF-8 tronqué au milieu. */
    static Truncation truncate(String text, int maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return new Truncation(text, false);
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.IGNORE)
                .onUnmappableCharacter(CodingErrorAction.IGNORE);
        try {
            String cut = decoder.decode(ByteBuffer.wrap(bytes, 0, maxBytes)).toString();
            return new Truncation(cut, true);
        } catch (CharacterCodingException e) {
            return new Truncation(text.substring(0, Math.min(text.length(), maxBytes)), true);
        }
    }

    private static String requiredText(JsonNode input, String field) {
        JsonNode value = input == null ? null : input.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new ToolException("invalid_input", "Paramètre requis manquant : " + field);
        }
        return value.asText();
    }

    private static String requiredContent(JsonNode input) {
        JsonNode value = input == null ? null : input.get("content");
        if (value == null || !value.isTextual()) {
            throw new ToolException("invalid_input", "Paramètre requis manquant : content");
        }
        return value.asText();
    }

    /** Texte éventuellement coupé, avec le drapeau {@code truncated} du contrat. */
    record Truncation(String text, boolean truncated) {
    }
}
