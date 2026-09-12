package fr.claudegateway.governance.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * <b>Ce que la carte a gagné</b> (F-93 / SF-93-02).
 *
 * <p>C'est la réponse mesurée à la phrase du PO : <i>« à chaque projet qu'on rajoute, la connaissance
 * de l'infra augmente »</i>. Sans elle, la promotion reste une corvée invisible — on la demande à
 * chaque tour et elle ne rend jamais rien à celui qui la fait.</p>
 *
 * <p><b>Ce n'est pas un tableau de bord.</b> Une phrase — « depuis le 2 septembre, la carte est
 * passée de 4 à 16 faits » — et jusqu'à six lignes de gains récents. Rien de plus : ce qu'on veut
 * produire est un <b>constat</b>, pas une page d'analyse.</p>
 *
 * <p><b>Absent quand il n'y a rien à dire.</b> Ce bloc est {@code null} tant qu'aucune observation
 * n'existe. Un « +0 » affiché chaque jour serait pire que rien : il apprendrait qu'on ne gagne
 * rien.</p>
 *
 * @param since      l'instant de la <b>première</b> observation de ce poste
 * @param sinceFacts ce que la carte portait alors
 * @param gained     ce qu'elle a gagné depuis, jamais négatif
 * @param recent     les derniers fichiers à avoir gagné, du plus récent au plus ancien
 */
public record GovernanceMapGrowthView(OffsetDateTime since, int sinceFacts, int gained,
        List<GovernanceMapGainView> recent) {
}
