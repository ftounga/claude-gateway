package fr.claudegateway.jarvis.state.extract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * <b>Le tri des sorties pour les constats sur l'historique</b> (F-247 / SF-247-17) : déterministe, sans IA, il dit
 * quelles sorties {@code bash} d'un tour passé valent un appel au modèle. Ne lit que les sorties utiles :
 * <ul>
 * <li>exploitables (code 0, entières, non vides) ;</li>
 * <li>non couvertes par un extracteur gratuit (ses faits sont déjà au registre) ;</li>
 * <li>hors outils de fichiers et de texte ({@code cat}, {@code sed}, {@code grep}…) : du contenu de fichier, pas un
 * état ;</li>
 * <li>sans rien qui touche un secret : commande qui lit un identifiant ({@code git credential}, trousseau, jeton…) ou
 * sortie qui en porte un — elle n'est <b>jamais</b> envoyée au modèle ;</li>
 * <li>ni trop courtes (rien à prouver) ni trop longues (au-delà de {@value StateNotesGuard#MAX_PER_OUTPUT} car.).</li>
 * </ul>
 * L'empreinte (SHA-256 du corps) écarte les doublons exacts et marque une sortie déjà constatée.
 */
public final class HistoryNotesSelector {

    /** En deçà, la sortie ne peut pas porter un objet et sa valeur. */
    public static final int MIN_BODY = 24;
    /** Au plus ≈ 3 500 jetons de sorties par appel : la réponse (1 500 jetons) reste assez large pour tout dire. */
    public static final int MAX_GROUP_CHARS = 12_000;
    /** Une commande qui lit ou manipule un identifiant : sa sortie ne part jamais au modèle. */
    private static final Pattern SECRET_COMMAND = Pattern.compile("(?i)(git\\s+credential|credential\\s+fill|"
            + "find-(generic|internet)-password|\\bsecurity\\s+(find|dump)|get-login-password|"
            + "get-authorization-token|get-secret-value|\\bauth\\s+token\\b|\\b(get|describe)\\s+secrets?\\b|"
            + "keyvault\\s+secret|\\bvault\\s+(read|kv)\\b|\\.netrc|\\.env\\b|private[_-]?key|\\bprintenv\\b|"
            + "^\\s*env\\s*$|\\bpasswd\\b|password\\s*=)");
    /** Une lecture d'identifiant capturée dans une variable ({@code T=$(… git credential fill …)}) n'est pas affichée. */
    private static final Pattern CAPTURED = Pattern.compile("\\b(\\w+)=\\$\\((?:[^()]|\\([^()]*\\))*\\)");

    public static final String KEPT = "retenue";

    /** Le verdict sur une sortie : retenue, ou le motif de l'écart. */
    public record Verdict(boolean kept, String reason, String command, String body, String fingerprint) {

        static Verdict skip(String reason) {
            return new Verdict(false, reason, null, null, null);
        }
    }

    private HistoryNotesSelector() {
    }

    /**
     * Classe une sortie {@code bash} relue de {@code terminal_json}. {@code extracted} : ce que les extracteurs gratuits
     * en ont tiré (une sortie couverte n'est pas relue).
     */
    public static Verdict classify(String blockCommand, String output, StateExtractors.Result extracted) {
        BashOutput o = BashOutput.parse(blockCommand, output);
        String command = o.command();
        String body = o.body();
        if (o.exitCode() == null || o.exitCode() != 0 || o.truncated()) {
            return Verdict.skip("inexploitable");
        }
        if (body == null || body.isBlank()) {
            return Verdict.skip("vide");
        }
        if (extracted != null && !extracted.facts().isEmpty()) {
            return Verdict.skip("couverte");
        }
        if (command == null || command.isBlank()) {
            return Verdict.skip("inexploitable");
        }
        // La commande montrée au modèle : sa première ligne, les lectures capturées réduites à « T=$(…) ».
        String shown = shortCommand(CAPTURED.matcher(command).replaceAll("$1=\\$(…)"));
        if (SECRET_COMMAND.matcher(CAPTURED.matcher(command).replaceAll(" ")).find() || SecretFilter.looksSecret(body)
                || SecretFilter.looksSecret(shown)) {
            return Verdict.skip("secret");
        }
        if (CommandShape.fileOnly(command)) {
            return Verdict.skip("fichiers");
        }
        if (body.length() < MIN_BODY) {
            return Verdict.skip("trop courte");
        }
        if (body.length() > StateNotesGuard.MAX_PER_OUTPUT) {
            return Verdict.skip("trop longue");
        }
        return new Verdict(true, KEPT, shown, body, fingerprint(body));
    }

    /** La commande telle que montrée au modèle : sa première ligne, bornée (le script en ligne n'aide pas à lire). */
    static String shortCommand(String command) {
        String first = command.strip().split("\n", 2)[0];
        return first.length() > 300 ? first.substring(0, 299) + "…" : first;
    }

    /** L'empreinte d'une sortie (SHA-256 hexadécimal du corps). */
    public static String fingerprint(String body) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(d.digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Une sortie retenue, prête à grouper. */
    public record Candidate(String toolUseId, String command, String body, String fingerprint,
            java.time.OffsetDateTime observedAt) {
    }

    /**
     * Regroupe les sorties d'un même tour par appel : au plus {@code maxChars} de sorties par groupe, dans l'ordre du
     * tour (une sortie n'est jamais coupée).
     */
    public static List<List<Candidate>> group(List<Candidate> candidates, int maxChars) {
        List<List<Candidate>> out = new ArrayList<>();
        List<Candidate> cur = new ArrayList<>();
        int size = 0;
        for (Candidate c : candidates) {
            int len = c.body().length() + c.command().length();
            if (!cur.isEmpty() && size + len > maxChars) {
                out.add(cur);
                cur = new ArrayList<>();
                size = 0;
            }
            cur.add(c);
            size += len;
        }
        if (!cur.isEmpty()) {
            out.add(cur);
        }
        return out;
    }
}
