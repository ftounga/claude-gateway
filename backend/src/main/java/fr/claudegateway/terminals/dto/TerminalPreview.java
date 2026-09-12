package fr.claudegateway.terminals.dto;

import java.time.OffsetDateTime;
import java.util.List;

import fr.claudegateway.terminals.TerminalActivity;

/**
 * <b>L'aperçu vivant d'un terminal</b> (F-76 / SF-76-01) : ce qu'il fait à l'instant, et ses
 * dernières lignes.
 *
 * <p><b>Un aperçu, pas un flux.</b> Le PO a écarté le rejeu de quatre flux complets : ce qu'on
 * cherche du coin de l'œil, c'est <i>est-ce que ça avance</i> et <i>est-ce que ça attend quelque
 * chose de moi</i>. Six lignes y répondent ; quatre transcriptions entières ne s'y lisent pas.</p>
 *
 * <p><b>La même forme aux deux densités</b> : quelques lignes sous le nom d'un projet sur l'accueil
 * de la Forge, l'aperçu complet dans une tuile de supervision. Une seule structure, deux tailles
 * d'affichage — l'écran décide combien il en montre, la gateway ne rend qu'une vérité.</p>
 *
 * @param activity       ce qui se passe ; {@code AWAITING_APPROVAL} est le seul état que
 *                       l'utilisateur doit voir tout de suite
 * @param activityDetail ce qui est en cours (« npm test »), déjà borné et nettoyé, ou {@code null}
 * @param lines          les dernières lignes, déjà bornées et nettoyées — jamais {@code null}
 * @param at             instant du relevé : c'est lui qui départage deux onglets sur le même projet
 */
public record TerminalPreview(
        TerminalActivity activity,
        String activityDetail,
        List<String> lines,
        OffsetDateTime at) {

    /** Normalise à la construction : pas de liste nulle à gérer dans les écrans ni dans les tris. */
    public TerminalPreview {
        activity = activity == null ? TerminalActivity.IDLE : activity;
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** Vrai si cet aperçu réclame une décision — ce qui le fait passer devant tous les autres. */
    public boolean awaitsApproval() {
        return activity.awaitsApproval();
    }

    /**
     * Vrai quand il n'y a rien à montrer : ni activité, ni détail, ni ligne. Un aperçu vide n'est
     * pas rendu — une tuile qui affiche « IDLE » et rien d'autre n'apprend rien de plus que la
     * pastille de vie déjà présente.
     */
    public boolean isEmpty() {
        return activity == TerminalActivity.IDLE
                && (activityDetail == null || activityDetail.isBlank())
                && lines.isEmpty();
    }
}
