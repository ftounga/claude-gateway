package fr.claudegateway.runner;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * La <b>version réelle</b> d'un runner (F-111 / SF-111-01) : numéro sémantique, date de construction
 * et commit, lus dans {@code runner-build.properties} que Maven filtre à la construction.
 *
 * <p>Jusqu'ici tous les postes annonçaient {@code 0.0.1} : la version venait d'une constante de repli
 * ou d'un {@code -SNAPSHOT} jamais bougé, et « son runner est-il à jour ? » n'avait pas de réponse.
 * L'<b>identifiant</b> {@code <version>-<AAAAMMJJHHmm>-<commit>} en a une : le numéro dit la version
 * annoncée, la date départage deux constructions au même numéro, le commit dit quel code tourne.</p>
 *
 * @param version numéro sémantique ({@code 1.0.0})
 * @param stamp   date de construction UTC {@code AAAAMMJJHHmm}, ou {@code null} si inconnue
 * @param commit  commit court, {@code local} pour une construction hors image, ou {@code null}
 */
public record RunnerBuild(String version, String stamp, String commit) {

    /**
     * Niveau de contrat runner ↔ gateway que ce runner parle (F-81, F-111). {@code 1} : le contrat
     * d'outils de F-38 avec la déclaration de version de F-111 ; {@code 2} : la commande {@code update}
     * (F-111 / SF-111-04). Il monte quand le runner apprend une
     * trame que la gateway doit savoir pouvoir lui envoyer.
     *
     * <p>Il est écrit <b>aussi</b> dans {@code runner-build.properties} (propriété {@code runner.contract}
     * du pom) : c'est là que la gateway le lit dans le jar qu'elle sert. Un test garde les deux égaux.</p>
     */
    public static final int CONTRACT = 2;

    /** Ressource filtrée par Maven (voir {@code pom.xml}). */
    static final String RESOURCE = "/runner-build.properties";

    /** Repli quand la ressource est absente ou non filtrée (exécution depuis un IDE). */
    static final String UNKNOWN_VERSION = "0.0.0";

    private static final Pattern SEMVER = Pattern.compile("\\d+(\\.\\d+){0,2}");
    private static final Pattern STAMP = Pattern.compile("\\d{12}");
    private static final Pattern COMMIT = Pattern.compile("[A-Za-z0-9]{1,40}");

    private static volatile RunnerBuild current;

    /** La construction de ce runner-ci, lue une fois. */
    public static RunnerBuild current() {
        RunnerBuild value = current;
        if (value == null) {
            value = read(RunnerBuild.class.getResourceAsStream(RESOURCE));
            current = value;
        }
        return value;
    }

    /**
     * Lit une construction depuis le flux d'un {@code runner-build.properties}. Ne lève jamais : un
     * flux absent, illisible ou non filtré rend {@code 0.0.0} sans date ni commit.
     */
    public static RunnerBuild read(InputStream in) {
        if (in == null) {
            return new RunnerBuild(UNKNOWN_VERSION, null, null);
        }
        Properties properties = new Properties();
        try (in) {
            properties.load(in);
        } catch (IOException | IllegalArgumentException e) {
            return new RunnerBuild(UNKNOWN_VERSION, null, null);
        }
        return of(properties.getProperty("version"), properties.getProperty("stamp"),
                properties.getProperty("commit"));
    }

    /** Construction à partir de valeurs brutes ; ce qui n'a pas la bonne forme est écarté. */
    public static RunnerBuild of(String version, String stamp, String commit) {
        String v = clean(version, SEMVER);
        return new RunnerBuild(v == null ? UNKNOWN_VERSION : v, clean(stamp, STAMP),
                clean(commit, COMMIT));
    }

    /**
     * Relit un <b>identifiant</b> {@code <version>-<stamp>-<commit>} (ou {@code <version>} seul).
     * Vide si l'identifiant n'a pas cette forme.
     */
    public static Optional<RunnerBuild> parseId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String[] parts = id.trim().split("-", 3);
        if (!SEMVER.matcher(parts[0]).matches()) {
            return Optional.empty();
        }
        String stamp = parts.length > 1 ? parts[1] : null;
        String commit = parts.length > 2 ? parts[2] : null;
        if (stamp != null && !STAMP.matcher(stamp).matches()) {
            return Optional.empty();
        }
        if (commit != null && !COMMIT.matcher(commit).matches()) {
            return Optional.empty();
        }
        return Optional.of(new RunnerBuild(parts[0], stamp, commit));
    }

    /** L'identifiant déclaré à la gateway et utilisé comme nom de dossier de version. */
    public String id() {
        StringBuilder id = new StringBuilder(version);
        if (stamp != null) {
            id.append('-').append(stamp);
            if (commit != null) {
                id.append('-').append(commit);
            }
        }
        return id.toString();
    }

    /**
     * Ordre des constructions : numéro sémantique, puis date quand les deux en ont une. Le commit ne
     * départage rien — deux constructions à la même minute du même numéro sont la même version.
     */
    public int compareTo(RunnerBuild other) {
        int[] a = numbers(version);
        int[] b = numbers(other.version);
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        if (stamp != null && other.stamp != null) {
            return stamp.compareTo(other.stamp);
        }
        return 0;
    }

    /** Version majeure de la JVM qui exécute ce code. */
    public static int javaMajor() {
        return Runtime.version().feature();
    }

    private static int[] numbers(String version) {
        int[] out = new int[3];
        String[] parts = version.split("\\.");
        for (int i = 0; i < Math.min(3, parts.length); i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    private static String clean(String raw, Pattern shape) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        return shape.matcher(value).matches() ? value : null;
    }
}
