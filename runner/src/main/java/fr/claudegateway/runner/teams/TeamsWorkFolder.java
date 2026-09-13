package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * <b>Le dossier de travail du volet Teams</b> (F-90 / SF-90-01), sur la machine et nulle part
 * ailleurs.
 *
 * <p>C'est là que vivent l'outillage téléchargé au premier usage (D3) et les images extraites d'un
 * enregistrement. Rien de ce qui s'y trouve ne remonte tel quel : la vidéo, l'audio et les fichiers
 * bruts <b>restent ici</b> — seules les images retenues et le compte rendu montent (§7 du cadrage).
 * <b>La gateway orchestre, elle ne devient pas un entrepôt de vidéos de réunions.</b></p>
 *
 * <p>L'emplacement reprend la convention déjà posée par le jeton et la mémoire de session :
 * {@code <racine du poste>/.claude-runner/}, avec repli sur {@code ~/.claude-runner/} quand la
 * racine n'est pas inscriptible. Un seul endroit sait écrire ce chemin — il n'est <b>jamais</b>
 * composé à partir d'un paramètre d'appel d'outil, ce qui est le premier des trois garde-fous de
 * l'exécution d'un binaire tiers.</p>
 */
public final class TeamsWorkFolder {

    /** Dossier commun du runner, déjà utilisé par le jeton (F-38) et la mémoire de session. */
    private static final String RUNNER_DIR = ".claude-runner";
    /** Sous-dossier du volet. */
    private static final String TEAMS_DIR = "teams";
    /** Sous-dossier de l'outillage téléchargé (D3) — <b>partagé avec F-91</b>. */
    private static final String TOOLS_DIR = "tools";
    /** Sous-dossier des enregistrements locaux (F-91 / SF-91-01). */
    private static final String CAPTURES_DIR = "captures";
    /** Sous-dossier des téléchargements faits par Chrome pour le volet (F-108 / SF-108-03). */
    private static final String DOWNLOADS_DIR = "downloads";

    private final Path root;

    /**
     * @param hostRoot racine du poste ; {@code null} force le repli sur le dossier du compte
     */
    public TeamsWorkFolder(Path hostRoot) {
        this.root = resolveRoot(hostRoot);
    }

    /** Racine du volet sur cette machine. */
    public Path root() {
        return root;
    }

    /**
     * Le dossier de l'outillage téléchargé au premier usage. <b>Fixe</b> : c'est le deuxième
     * garde-fou — la destination d'un téléchargement ne vient jamais d'un appel d'outil.
     */
    public Path toolsDir() {
        return root.resolve(TOOLS_DIR);
    }

    /**
     * Le dossier des enregistrements locaux (F-91 / SF-91-01). <b>Fixe</b>, comme celui de
     * l'outillage : la destination d'une capture ne vient <b>jamais</b> d'un appel d'outil. C'est le
     * garde-fou qui empêche un paramètre venu du modèle de faire écrire des centaines de mégaoctets
     * n'importe où sur la machine — y compris par-dessus quelque chose.
     */
    public Path capturesDir() {
        return root.resolve(CAPTURES_DIR);
    }

    /**
     * Le dossier où <b>Chrome</b> dépose ce qu'il télécharge pour le volet (F-108 / SF-108-03).
     * <b>Fixe</b>, comme les autres : un paramètre d'appel ne choisit jamais où atterrissent des
     * octets venus de Microsoft 365.
     */
    public Path downloadsDir() {
        return root.resolve(DOWNLOADS_DIR);
    }

    /** Le dossier fixe d'un téléchargement, nommé par caractères sûrs. */
    public Path downloadDir(String name) {
        return downloadsDir().resolve(safe(name));
    }

    /**
     * Un dossier de travail propre pour un traitement, créé à la demande.
     *
     * @param name nom du travail, réduit à des caractères sûrs : ce n'est jamais un chemin
     */
    public Path workDir(String name) throws IOException {
        Path dir = root.resolve("work").resolve(safe(name));
        Files.createDirectories(dir);
        return dir;
    }

    /** Crée le dossier s'il manque, et rend son chemin. */
    public Path ensure(Path dir) throws IOException {
        Files.createDirectories(dir);
        return dir;
    }

    /**
     * Un nom de dossier, jamais un chemin : lettres, chiffres, tiret et souligné. Ce qui exclut
     * {@code ..}, les séparateurs, et tout ce qui en ferait une traversée.
     */
    static String safe(String name) {
        String value = name == null ? "" : name.strip();
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < value.length() && out.length() < 64; index++) {
            char c = value.charAt(index);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                out.append(c);
            }
        }
        return out.length() == 0 ? "sans-nom" : out.toString();
    }

    /**
     * La racine du poste si elle est inscriptible, sinon le dossier du compte. Le repli n'est pas
     * une commodité : un poste dont le projet est en lecture seule doit quand même pouvoir
     * travailler.
     */
    private static Path resolveRoot(Path hostRoot) {
        if (hostRoot != null) {
            Path candidate = hostRoot.toAbsolutePath().normalize().resolve(RUNNER_DIR)
                    .resolve(TEAMS_DIR);
            if (isUsable(candidate)) {
                return candidate;
            }
        }
        return Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize()
                .resolve(RUNNER_DIR).resolve(TEAMS_DIR);
    }

    /** Inscriptible veut dire : on a pu le créer, ou il existe et on peut y écrire. */
    private static boolean isUsable(Path dir) {
        try {
            Files.createDirectories(dir);
            return Files.isWritable(dir);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
