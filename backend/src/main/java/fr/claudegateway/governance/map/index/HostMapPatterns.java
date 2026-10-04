package fr.claudegateway.governance.map.index;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>La couche déterministe de l'extraction</b> (F-174 / SF-174-02, D2 a).
 *
 * <p>Ce que des motifs reconnaissent à coup sûr : un compte AWS à 12 chiffres, un ARN, une URL, une
 * adresse IP ou un CIDR, un domaine, un dépôt {@code groupe/projet} ; la date de constat ; une
 * échéance datée ; la marque d'un piège. Aucune interprétation : ce qui est reconnu figure mot pour
 * mot dans la carte. C'est ce qui garantit l'exactitude des identifiants, quoi que dise le modèle.</p>
 */
public final class HostMapPatterns {

    /** Un identifiant reconnu, avec sa nature. */
    public record Identifier(String kind, String value) {
    }

    public static final String AWS_ACCOUNT = "compte_aws";
    public static final String ARN = "arn";
    public static final String URL = "url";
    public static final String IP = "ip";
    public static final String DOMAIN = "domaine";
    public static final String REPOSITORY = "depot";

    private static final Pattern ARN_PATTERN =
            Pattern.compile("arn:aws[a-z-]*:[a-z0-9-]*:[a-z0-9-]*:\\d{0,12}:[^\\s`'\"<>)\\],;]+",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://[^\\s`'\"<>)\\]]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACCOUNT_PATTERN = Pattern.compile("(?<![\\d.:/-])\\d{12}(?![\\d])");
    private static final Pattern IP_PATTERN = Pattern.compile(
            "(?<![\\d.])(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)"
                    + "(?:/(?:3[0-2]|[12]?\\d))?(?![\\d.])");
    private static final Pattern DOMAIN_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9@/._-])(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,24}"
                    + "(?![A-Za-z0-9_-]|\\.[A-Za-z0-9])");
    private static final Pattern REPOSITORY_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9_./:@~-])([A-Za-z][A-Za-z0-9_.-]*(?:/[A-Za-z0-9][A-Za-z0-9_.-]*){1,3})"
                    + "(?![A-Za-z0-9_/-])");
    /** Une ligne qui parle d'un dépôt : sans ce contexte, « a/b » est trop souvent autre chose. */
    private static final Pattern REPOSITORY_CONTEXT = Pattern.compile(
            "d[ée]p[ôo]t|repo|gitlab|github|forge|projet|clone|merge|\\bmr\\b|\\bpr\\b",
            Pattern.CASE_INSENSITIVE);

    /** Extensions de fichier : « acces.md » n'est pas un domaine. */
    private static final Set<String> FILE_EXTENSIONS = Set.of("md", "txt", "json", "yml", "yaml",
            "xml", "sh", "java", "ts", "js", "py", "log", "csv", "conf", "cfg", "pem", "key", "crt",
            "ini", "properties", "html", "css", "sql", "tf", "tfvars", "zip", "tar", "gz", "jar",
            "exe", "ps1", "bat", "png", "jpg", "pdf", "docx", "xlsx", "pptx", "env", "lock", "toml",
            "go", "rs", "kt", "rb", "php", "mjs", "cjs", "tsx", "jsx", "vue", "scss", "svg", "ico");

    private static final Pattern DATE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern CONSTAT =
            Pattern.compile("constat[ée]e?\\s+le\\s+(\\d{4}-\\d{2}-\\d{2})", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEADLINE_CONTEXT = Pattern.compile(
            "p[ée]rim|expir|[ée]ch[ée]ance|jusqu|avant le|renouvel|valable|valide jusqu|deadline",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PITFALL = Pattern.compile(
            "pi[èe]ge|⚠|attention|ne jamais|ne pas |interdit|ne fonctionne pas|ne marche pas"
                    + "|bloqu|cause r[ée]elle|gotcha|contre-intuiti",
            Pattern.CASE_INSENSITIVE);

    private HostMapPatterns() {
    }

    /** Les identifiants exacts d'une ligne, sans doublon, dans l'ordre d'apparition. */
    public static List<Identifier> identifiers(String line) {
        if (line == null || line.isBlank()) {
            return List.of();
        }
        Map<String, Identifier> found = new LinkedHashMap<>();
        // Les ARN et les URL d'abord, et on les retire du texte : un domaine ou un compte pris
        // DANS une URL ou un ARN n'est pas un second identifiant.
        String rest = collect(line, ARN_PATTERN, ARN, found);
        rest = collect(rest, URL_PATTERN, URL, found);
        rest = collect(rest, IP_PATTERN, IP, found);
        rest = collect(rest, ACCOUNT_PATTERN, AWS_ACCOUNT, found);
        Matcher domains = DOMAIN_PATTERN.matcher(rest);
        while (domains.find()) {
            String domain = domains.group();
            String tld = domain.substring(domain.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            if (FILE_EXTENSIONS.contains(tld) || domain.indexOf('.') <= 0) {
                continue;
            }
            put(found, DOMAIN, domain);
        }
        if (REPOSITORY_CONTEXT.matcher(line).find()) {
            Matcher repositories = REPOSITORY_PATTERN.matcher(rest);
            while (repositories.find()) {
                String repository = repositories.group(1);
                String last = repository.substring(repository.lastIndexOf('/') + 1);
                int dot = last.lastIndexOf('.');
                if (dot > 0 && FILE_EXTENSIONS.contains(last.substring(dot + 1).toLowerCase(Locale.ROOT))) {
                    continue; // « docs/acces.md » est un chemin de fichier.
                }
                if (repository.length() < 5) {
                    continue; // « et/ou », « a/b » : trop court pour être un dépôt.
                }
                put(found, REPOSITORY, repository);
            }
        }
        return new ArrayList<>(found.values());
    }

    /** La date de constat d'une ligne ({@code constaté le AAAA-MM-JJ}), ou {@code null}. */
    public static LocalDate observedOn(String line) {
        if (line == null) {
            return null;
        }
        Matcher matcher = CONSTAT.matcher(line);
        return matcher.find() ? parse(matcher.group(1)) : null;
    }

    /**
     * L'échéance d'une ligne : une date qui suit un mot d'échéance (« périme le », « expire »,
     * « jusqu'au »…) et qui n'est pas la date de constat. {@code null} sinon.
     */
    public static LocalDate deadline(String line) {
        if (line == null || !DEADLINE_CONTEXT.matcher(line).find()) {
            return null;
        }
        String withoutConstat = CONSTAT.matcher(line).replaceAll(" ");
        Matcher date = DATE.matcher(withoutConstat);
        while (date.find()) {
            LocalDate parsed = parse(date.group(1));
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    /** La ligne porte-t-elle la marque d'un piège ? */
    public static boolean isPitfall(String line) {
        return line != null && PITFALL.matcher(line).find();
    }

    private static String collect(String text, Pattern pattern, String kind,
            Map<String, Identifier> found) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder rest = new StringBuilder();
        while (matcher.find()) {
            String value = trimTrailing(matcher.group());
            put(found, kind, value);
            matcher.appendReplacement(rest, " ");
        }
        matcher.appendTail(rest);
        return rest.toString();
    }

    private static void put(Map<String, Identifier> found, String kind, String value) {
        String key = value.toLowerCase(Locale.ROOT);
        if (!value.isBlank() && !found.containsKey(key)) {
            found.put(key, new Identifier(kind, value));
        }
    }

    /** La ponctuation de fin de phrase ne fait pas partie de l'identifiant. */
    private static String trimTrailing(String value) {
        String trimmed = value;
        while (!trimmed.isEmpty() && ".,;:!?".indexOf(trimmed.charAt(trimmed.length() - 1)) >= 0) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static LocalDate parse(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
