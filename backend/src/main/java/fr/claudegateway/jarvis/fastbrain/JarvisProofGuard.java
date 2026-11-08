package fr.claudegateway.jarvis.fastbrain;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>La garde « preuve sans oui »</b> (F-237 / SF-237-15, cadrage §8) — déterministe, sans appel au modèle.
 *
 * <p>Une parole de <b>doute</b> (« tu es sûr ? », « tu as vérifié ? », « prouve-moi… ») ne se délègue jamais à Opus :
 * on montre les preuves existantes, ou on <b>propose</b> de les générer et on attend le « oui ». Le « oui » du tour
 * suivant n'est pas un doute : la délégation part. Une demande explicite d'agir (« génère la preuve », « teste-le »,
 * « vérifie ») n'est pas gardée : c'est déjà le consentement.</p>
 *
 * <p>Tout est lu dans le contexte déjà construit (sous {@code user_id}) : le bloc {@code ## Pièces} (un compteur par
 * chantier) et la ligne « Chantier courant ». Aucune lecture en base.</p>
 */
public final class JarvisProofGuard {

    /** Les tournures de doute (normalisées, sans accents). */
    private static final Pattern DOUBT = Pattern.compile("\\b(tu es sur|t es sur|es tu sur|vous etes sur|"
            + "etes vous sur|sur et certain|tu as verifie|t as verifie|as tu verifie|vous avez verifie|"
            + "avez vous verifie|c est verifie|c est bien verifie|tu l as verifie|tu as teste|t as teste|"
            + "as tu teste|prouve|prouvez|prouver)\\b");
    /** Une demande explicite d'agir : c'est le consentement, la garde s'efface. */
    private static final Pattern EXPLICIT_ACT = Pattern.compile("\\b(genere|generez|generer|"
            + "capture la|capture les|capturez|capturer|lance|lancez|refais|refaites)\\b");
    /** Un impératif en tête de proposition (« Tu es sûr ? Vérifie. ») : c'est aussi le consentement. */
    private static final Pattern IMPERATIVE = Pattern.compile("^(?:(?:et|alors|donc|bon|ok|jarvis) )*"
            + "(verifie|verifiez|teste|testez|teste le|testez le|teste la|testez la)\\b");
    private static final Pattern NAMED_CHANTIER = Pattern.compile("\\bchantier (\\d{1,4})\\b");
    private static final Pattern CURRENT_CHANTIER = Pattern.compile("(?m)^Chantier courant : (\\d{1,4})\\b");
    private static final Pattern PIECES_LINE = Pattern.compile("(?m)^chantier (\\d{1,4}) : (.*)$");
    private static final Pattern PROOF_COUNT = Pattern.compile("(\\d+) preuves?\\b");

    /** La proposition dite quand le chantier n'a aucune preuve. */
    static String propose(int chantier) {
        return "Le chantier " + chantier + " n'a encore aucune preuve en image. Voulez-vous que je la génère, "
                + "Monsieur ?";
    }

    /** La proposition dite quand ni le chantier ni ses preuves ne sont connus. */
    static final String PROPOSE_CHECK = "Voulez-vous que je vérifie, Monsieur ?";

    /** La phrase dite en montrant les preuves existantes. */
    static String showing(int chantier) {
        return "Voici les preuves du chantier " + chantier + ".";
    }

    /** Ce que la garde décide : une phrase fixe et, éventuellement, un ordre d'écran. */
    public record Decision(String said, JarvisShowCommand show) {
    }

    private JarvisProofGuard() {
    }

    /** Vrai si la parole est un doute sur une fin, une vérification ou une preuve, sans demande explicite d'agir. */
    public static boolean guards(String said) {
        String n = JarvisFastGuards.normalize(said);
        if (n.isEmpty() || !DOUBT.matcher(n).find() || EXPLICIT_ACT.matcher(n).find()) {
            return false;
        }
        for (String clause : said.split("[.?!;,:…]+")) {
            if (IMPERATIVE.matcher(JarvisFastGuards.normalize(clause)).find()) {
                return false;
            }
        }
        return true;
    }

    /** Le chantier visé : nommé dans la parole, sinon « Chantier courant » du contexte, sinon celui de la requête. */
    static Integer chantier(String said, String context, Integer requestChantier) {
        Matcher named = NAMED_CHANTIER.matcher(JarvisFastGuards.normalize(said));
        if (named.find()) {
            return Integer.valueOf(named.group(1));
        }
        Matcher current = CURRENT_CHANTIER.matcher(context == null ? "" : context);
        if (current.find()) {
            return Integer.valueOf(current.group(1));
        }
        return requestChantier;
    }

    /**
     * Le nombre de preuves du chantier d'après le bloc {@code ## Pièces} ; {@code null} si le bloc est absent (inconnu).
     * Bloc présent sans ligne pour ce chantier : aucune pièce, donc 0 preuve.
     */
    static Integer proofs(String context, Integer chantier) {
        if (context == null || chantier == null) {
            return null;
        }
        int block = context.indexOf("## Pièces");
        if (block < 0) {
            return null;
        }
        String after = context.substring(block);
        int next = after.indexOf("\n## ", 1);
        Matcher line = PIECES_LINE.matcher(next < 0 ? after : after.substring(0, next));
        while (line.find()) {
            if (Integer.parseInt(line.group(1)) == chantier) {
                Matcher count = PROOF_COUNT.matcher(line.group(2));
                return count.find() ? Integer.valueOf(count.group(1)) : 0;
            }
        }
        return 0;
    }

    /**
     * <b>Avant l'appel au modèle</b> : un doute sur un chantier sans aucune preuve ⇒ la proposition, tout de suite, sans
     * appel. {@code null} : le modèle décide (il sait montrer les preuves existantes).
     */
    public static Decision before(String said, String context, Integer requestChantier) {
        if (!guards(said)) {
            return null;
        }
        Integer chantier = chantier(said, context, requestChantier);
        Integer proofs = proofs(context, chantier);
        return proofs != null && proofs == 0 ? new Decision(propose(chantier), null) : null;
    }

    /**
     * <b>Le filet après l'appel</b> : un doute ne part jamais à Opus. Preuves existantes ⇒ la galerie des preuves du
     * chantier ; aucune ⇒ la proposition ; inconnu ⇒ « Voulez-vous que je vérifie ? ». {@code null} si la parole n'est
     * pas gardée.
     */
    public static Decision instead(String said, String context, Integer requestChantier, Integer delegatedChantier) {
        if (!guards(said)) {
            return null;
        }
        Integer chantier = chantier(said, context, delegatedChantier != null ? delegatedChantier : requestChantier);
        Integer proofs = proofs(context, chantier);
        if (chantier == null || proofs == null) {
            return new Decision(PROPOSE_CHECK, null);
        }
        if (proofs == 0) {
            return new Decision(propose(chantier), null);
        }
        JarvisShowCommand gallery = new JarvisShowCommand("galerie", List.of(),
                new JarvisShowCommand.Scope(null, chantier, "preuves", null, null, null), null, null, null, null, null,
                null, "appui", null, null);
        return new Decision(showing(chantier), gallery);
    }
}
