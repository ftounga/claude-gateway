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
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Outils fichiers exécutés <b>sur la machine de l'utilisateur</b> (F-38 / SF-38-04) :
 * {@code list_files}, {@code read_file}, {@code write_file}, {@code search_files} — et, depuis F-110 / SF-110-03,
 * {@code read_file_bytes}, la lecture <b>binaire</b> par tranches qui sert aux pièces jointes d'un courriel.
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

    /**
     * Plafond d'un fichier lu par {@code read_file_bytes} (F-110 / SF-110-03) : celui d'un courriel entier. Au-delà,
     * le fichier ne partira jamais en pièce jointe — inutile d'en lire une tranche.
     */
    static final long MAX_BYTES_FILE = 10L * 1024 * 1024;

    /**
     * Tranche maximale de {@code read_file_bytes} : ses octets encodés en Base64 (× 4/3) tiennent dans la borne
     * du champ {@code content} (512 Kio), donc dans la trame de 1 Mio.
     */
    static final int MAX_BYTES_CHUNK = 393_216;

    /**
     * Plafond d'un fichier <b>déposé</b> par {@code write_file_bytes} (F-115 / SF-115-01) : 100 Mio.
     * Contrôlé à chaque tranche par {@code offset + longueur}, pour refuser un dépôt qui déborde sans
     * attendre la fin du transfert découpé.
     */
    static final long MAX_DEPOSIT_BYTES = 100L * 1024 * 1024;

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
                case "grep" -> grep(input);
                case "glob" -> glob(input);
                case "read_file_bytes" -> readFileBytes(requiredText(input, "path"), input);
                case "write_file_bytes" -> writeFileBytes(requiredText(input, "path"), input);
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

    /**
     * Une tranche <b>binaire</b> d'un fichier, encodée en Base64 (F-110 / SF-110-03).
     *
     * <p>{@code offset} (défaut 0) et {@code length} (défaut et maximum {@link #MAX_BYTES_CHUNK}) bornent la
     * tranche. Le résultat porte la <b>taille totale</b> du fichier dans {@code bytes} — c'est elle qui permet à
     * la gateway de refuser un courriel trop lourd dès la première tranche, et de voir un fichier qui change
     * pendant la lecture — et {@code truncated} vaut vrai tant qu'il reste des octets après la tranche.</p>
     */
    ToolOutcome readFileBytes(String rawPath, JsonNode input) throws IOException {
        long offset = optionalLong(input, "offset", 0L);
        long length = optionalLong(input, "length", MAX_BYTES_CHUNK);
        if (offset < 0) {
            throw new ToolException("invalid_input", "offset doit être positif ou nul.");
        }
        if (length < 1 || length > MAX_BYTES_CHUNK) {
            throw new ToolException("invalid_input",
                    "length doit être compris entre 1 et " + MAX_BYTES_CHUNK + " octets.");
        }
        PathResolver.Resolved resolved = paths.resolve(rawPath);
        Path path = resolved.path();
        requireExistingFile(resolved);
        long size = Files.size(path);
        if (size > MAX_BYTES_FILE) {
            throw new ToolException("too_large",
                    "Fichier trop volumineux pour une pièce jointe (10 Mo au plus) : " + resolved.display());
        }
        if (offset > size) {
            throw new ToolException("invalid_input", "offset au-delà de la fin du fichier : " + resolved.display());
        }
        int wanted = (int) Math.min(length, size - offset);
        ByteBuffer buffer = ByteBuffer.allocate(wanted);
        try (java.nio.channels.SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            channel.position(offset);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    break;
                }
            }
        } catch (IOException e) {
            throw new ToolException("io_error", "Lecture impossible : " + resolved.display());
        }
        byte[] chunk = java.util.Arrays.copyOf(buffer.array(), buffer.position());
        boolean remaining = offset + chunk.length < size;
        return ToolOutcome.ok(java.util.Base64.getEncoder().encodeToString(chunk), remaining, size);
    }

    /**
     * Écrit une <b>tranche binaire</b> d'un fichier déposé (F-115 / SF-115-01), symétrique de
     * {@link #readFileBytes}. {@code content} porte la tranche en Base64, {@code offset} (défaut 0) sa
     * position dans le fichier : la tranche à {@code offset == 0} <b>tronque et crée</b> le fichier,
     * les suivantes sont écrites à leur position. Sert au transfert découpé d'un gros fichier vers
     * {@code .atelier/entrees/} sans jamais faire passer le fichier entier en une trame.
     *
     * <p>Le chemin est re-validé par {@link PathResolver} (sous la racine, jamais {@code ..}) — la
     * gateway a beau assainir le nom, le runner reste l'autorité (contrat F-38, D6). La taille
     * cumulée ({@code offset + tranche}) est bornée à {@link #MAX_DEPOSIT_BYTES}.</p>
     */
    ToolOutcome writeFileBytes(String rawPath, JsonNode input) throws IOException {
        long offset = optionalLong(input, "offset", 0L);
        if (offset < 0) {
            throw new ToolException("invalid_input", "offset doit être positif ou nul.");
        }
        byte[] chunk;
        try {
            chunk = java.util.Base64.getDecoder().decode(requiredContent(input));
        } catch (IllegalArgumentException e) {
            throw new ToolException("invalid_input", "Contenu Base64 invalide.");
        }
        if (chunk.length > MAX_BYTES_CHUNK) {
            throw new ToolException("invalid_input",
                    "Tranche trop grande (" + MAX_BYTES_CHUNK + " octets au plus).");
        }
        if (offset + (long) chunk.length > MAX_DEPOSIT_BYTES) {
            throw new ToolException("too_large", "Fichier trop volumineux pour un dépôt (100 Mo au plus).");
        }
        PathResolver.Resolved resolved = paths.resolve(rawPath);
        Path path = resolved.path();
        if (Files.isDirectory(path)) {
            throw new ToolException("is_directory", "Le chemin est un dossier : " + resolved.display());
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path)) {
            throw new ToolException("not_a_file", "Le chemin n'est pas un fichier : " + resolved.display());
        }
        Path parent = path.getParent();
        boolean truncate = offset == 0;
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            StandardOpenOption[] options = truncate
                    ? new StandardOpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING}
                    : new StandardOpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.WRITE};
            try (java.nio.channels.SeekableByteChannel channel = Files.newByteChannel(path, options)) {
                channel.position(offset);
                ByteBuffer buffer = ByteBuffer.wrap(chunk);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
            }
        } catch (IOException e) {
            throw new ToolException("io_error", "Écriture impossible : " + resolved.display());
        }
        long total = Files.size(path);
        return ToolOutcome.ok("Tranche écrite : " + resolved.display(), false, total);
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

    // ------------------------------------------------------------ grep / glob (F-121 / SF-121-01)

    /**
     * <b>Grep</b> : une vraie recherche par <b>expression régulière</b> sur l'arbre du projet
     * (F-121 / SF-121-01), en un seul appel, sans passer par le shell — donc identique sur un poste
     * {@code cmd.exe} qui n'a ni {@code grep} ni {@code rg}.
     *
     * <p>Paramètres : {@code pattern} (regex, requis) ; {@code path} (sous-arbre, optionnel) ;
     * {@code include} (motif glob de nom de fichier, optionnel) ; {@code ignore_case} (booléen) ;
     * {@code output_mode} ∈ {@code content} (défaut) / {@code files_with_matches} / {@code count} ;
     * {@code before}/{@code after}/{@code context} (lignes de contexte, {@code -B}/{@code -A}/{@code -C}).</p>
     */
    ToolOutcome grep(JsonNode input) throws IOException {
        String rawPattern = requiredText(input, "pattern");
        if (rawPattern.length() > 1024) {
            throw new ToolException("invalid_input", "Motif trop long (1024 caractères au plus).");
        }
        int flags = optionalBool(input, "ignore_case") ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
        java.util.regex.Pattern pattern;
        try {
            pattern = java.util.regex.Pattern.compile(rawPattern, flags);
        } catch (java.util.regex.PatternSyntaxException e) {
            throw new ToolException("invalid_input", "Expression régulière invalide.");
        }
        Path base = scopeBase(input);
        String include = optionalText(input, "include");
        String mode = grepMode(input);
        int before = boundedContext(input, "before");
        int after = boundedContext(input, "after");
        int context = boundedContext(input, "context");
        before = Math.max(before, context);
        after = Math.max(after, context);

        StringBuilder result = new StringBuilder();
        for (String relative : walkFiles(base)) {
            checkNotInterrupted();
            if (include != null && !includeMatches(relative, include)) {
                continue;
            }
            String content = readSearchable(paths.root().resolve(relative));
            if (content == null) {
                continue;
            }
            String[] lines = content.split("\n", -1);
            java.util.List<Integer> matches = new ArrayList<>();
            for (int i = 0; i < lines.length; i++) {
                if (pattern.matcher(lines[i]).find()) {
                    matches.add(i);
                }
            }
            if (matches.isEmpty()) {
                continue;
            }
            switch (mode) {
                case "files_with_matches" -> result.append(relative).append('\n');
                case "count" -> result.append(relative).append(':').append(matches.size()).append('\n');
                default -> appendGrepContent(result, relative, lines, matches, before, after);
            }
            if (result.length() > SEARCH_MAX_CHARS) {
                return ToolOutcome.ok(result.append("… (résultats tronqués)").toString(), true, -1);
            }
        }
        return result.isEmpty() ? ToolOutcome.ok("Aucun résultat.") : ToolOutcome.ok(result.toString());
    }

    /** Écrit les lignes correspondantes et leur contexte au format {@code chemin:ligne: texte} / {@code chemin-ligne- texte}. */
    private static void appendGrepContent(StringBuilder result, String relative, String[] lines,
            java.util.List<Integer> matches, int before, int after) {
        java.util.SortedSet<Integer> matchSet = new java.util.TreeSet<>(matches);
        // Les indices à émettre : chaque correspondance et sa fenêtre de contexte, sans doublon.
        java.util.SortedSet<Integer> emit = new java.util.TreeSet<>();
        for (int m : matches) {
            for (int i = Math.max(0, m - before); i <= Math.min(lines.length - 1, m + after); i++) {
                emit.add(i);
            }
        }
        int previous = -2;
        for (int i : emit) {
            if ((before > 0 || after > 0) && previous >= 0 && i > previous + 1) {
                result.append("--\n"); // séparateur de groupes non contigus, comme grep -C
            }
            char sep = matchSet.contains(i) ? ':' : '-';
            result.append(relative).append(sep).append(i + 1).append(sep == ':' ? ": " : "- ")
                    .append(lines[i].strip()).append('\n');
            previous = i;
        }
    }

    /**
     * <b>Glob</b> : les fichiers dont le chemin relatif correspond au motif (F-121 / SF-121-01),
     * <b>triés par date de modification décroissante</b> (le plus récent d'abord — l'ordre utile
     * quand on cherche « ce que je viens de toucher »). Un par ligne.
     */
    ToolOutcome glob(JsonNode input) throws IOException {
        String rawPattern = requiredText(input, "pattern");
        if (rawPattern.length() > 1024) {
            throw new ToolException("invalid_input", "Motif trop long (1024 caractères au plus).");
        }
        java.nio.file.PathMatcher matcher;
        try {
            matcher = paths.root().getFileSystem().getPathMatcher("glob:" + rawPattern);
        } catch (RuntimeException e) {
            throw new ToolException("invalid_input", "Motif glob invalide.");
        }
        Path base = scopeBase(input);
        java.util.List<String> matched = new ArrayList<>();
        java.util.Map<String, Long> mtimes = new java.util.HashMap<>();
        for (String relative : walkFiles(base)) {
            checkNotInterrupted();
            if (!matcher.matches(paths.root().getFileSystem().getPath(relative))) {
                continue;
            }
            matched.add(relative);
            try {
                mtimes.put(relative, Files.getLastModifiedTime(paths.root().resolve(relative)).toMillis());
            } catch (IOException | RuntimeException e) {
                mtimes.put(relative, 0L); // fichier disparu en cours de balayage : au fond du tri
            }
        }
        // Tri : mtime décroissant, chemin croissant pour départager (ordre déterministe).
        matched.sort(java.util.Comparator.<String>comparingLong(p -> -mtimes.getOrDefault(p, 0L))
                .thenComparing(java.util.Comparator.naturalOrder()));
        if (matched.isEmpty()) {
            return ToolOutcome.ok("Aucun résultat.");
        }
        Truncation body = truncate(String.join("\n", matched), MAX_CONTENT_BYTES);
        return ToolOutcome.ok(body.text(), body.truncated(), -1);
    }

    /** Base du balayage de {@code grep}/{@code glob} : le sous-arbre {@code path}, ou la racine du projet. */
    private Path scopeBase(JsonNode input) {
        String scope = optionalText(input, "path");
        if (scope == null) {
            return paths.root();
        }
        PathResolver.Resolved resolved = paths.resolve(scope);
        return Files.isDirectory(resolved.path()) ? resolved.path() : paths.root();
    }

    /** Mode de sortie de {@code grep} : {@code content} (défaut), {@code files_with_matches}, {@code count}. */
    private static String grepMode(JsonNode input) {
        String mode = optionalTextStatic(input, "output_mode");
        if ("files_with_matches".equals(mode) || "count".equals(mode)) {
            return mode;
        }
        return "content";
    }

    /**
     * Un chemin relatif correspond-il au motif {@code include} de {@code grep} ? Un motif sans
     * {@code /} porte sur le <b>nom de fichier</b> (comportement de {@code rg --include}), un motif
     * avec {@code /} sur le chemin relatif complet.
     */
    private boolean includeMatches(String relative, String include) {
        String candidate = include.indexOf('/') >= 0 ? relative
                : relative.substring(relative.lastIndexOf('/') + 1);
        try {
            return paths.root().getFileSystem().getPathMatcher("glob:" + include)
                    .matches(paths.root().getFileSystem().getPath(candidate));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static int boundedContext(JsonNode input, String field) {
        JsonNode value = input == null ? null : input.get(field);
        if (value == null || !value.isIntegralNumber()) {
            return 0;
        }
        int n = value.asInt();
        return n < 0 ? 0 : Math.min(n, 100);
    }

    private static boolean optionalBool(JsonNode input, String field) {
        JsonNode value = input == null ? null : input.get(field);
        return value != null && value.asBoolean(false);
    }

    private String optionalText(JsonNode input, String field) {
        return optionalTextStatic(input, field);
    }

    private static String optionalTextStatic(JsonNode input, String field) {
        JsonNode value = input == null ? null : input.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText().trim();
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
        return walkFiles(paths.root());
    }

    /**
     * Balayage à partir d'un dossier <b>de base</b> (F-121 / SF-121-01) : identique à
     * {@link #walkFiles()}, mais démarré ailleurs que la racine du projet — c'est ce qui donne à
     * {@code grep}/{@code glob} leur paramètre {@code path} (sous-arbre de recherche). Les chemins
     * restent relativisés à la racine du projet pour que le format de sortie ne dépende pas de la
     * portée demandée ; les {@link ExclusionRules} s'appliquent toujours au balayage.
     */
    private List<String> walkFiles(Path base) throws IOException {
        ExclusionRules exclusions = paths.exclusions();
        List<String> found = new ArrayList<>();
        Files.walkFileTree(base, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
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

    private static long optionalLong(JsonNode input, String field, long fallback) {
        JsonNode value = input == null ? null : input.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new ToolException("invalid_input", "Paramètre entier attendu : " + field);
        }
        return value.asLong();
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
