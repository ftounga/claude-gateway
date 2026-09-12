package fr.claudegateway.governance.dto;

import java.util.List;
import java.util.UUID;

/**
 * Un fichier du paquet, <b>ouvert avant d'accepter</b> (F-75 / SF-75-02).
 *
 * <p>Jusqu'ici l'écran annonçait le <b>chemin</b> et le <b>type</b> de chaque fichier, jamais son
 * <b>contenu</b> : on demandait d'approuver un dépôt de fichiers <b>à l'aveugle, sur la machine d'un
 * client</b>. Ce que l'utilisateur a pu lire avant d'activer est la seule chose qui reste entre lui et
 * l'inattendu — il faut donc qu'il puisse lire.</p>
 *
 * <p><b>Et le différentiel.</b> Le dépôt est idempotent : il n'écrase jamais. Quand le fichier existe
 * déjà, ce n'est donc pas celui du paquet qui s'appliquera, c'est celui qui est en place — et c'est
 * précisément ce qu'il faut voir avant de dire oui.</p>
 *
 * @param path      chemin relatif, tel qu'il sera écrit
 * @param kind      {@code SKILL} ou {@code TEMPLATE}
 * @param content   contenu <b>apporté par le paquet</b>, tel quel
 * @param truncated vrai si le contenu apporté a été coupé à la borne de lecture
 * @param projects  ce que chaque dossier du poste porte aujourd'hui sous ce chemin
 * @param omitted   dossiers non inspectés, au-delà du plafond ; zéro dans le cas normal
 */
public record GovernanceFileComparison(String path, String kind, String content, boolean truncated,
        List<ProjectFile> projects, int omitted) {

    /**
     * Ce qu'<b>un</b> dossier porte aujourd'hui sous ce chemin.
     *
     * @param workspaceId dossier concerné
     * @param name        son nom lisible
     * @param readable    faux si le dossier n'a pas pu être lu — machine éteinte
     * @param exists      vrai si un fichier porte déjà ce chemin ; il sera alors <b>laissé tel quel</b>
     * @param identical   vrai si ce fichier est déjà exactement celui du paquet — rien ne changerait
     * @param content     contenu <b>actuel</b> du fichier, ou {@code null} s'il n'existe pas
     * @param truncated   vrai si ce contenu a été coupé à la borne de lecture
     */
    public record ProjectFile(UUID workspaceId, String name, boolean readable, boolean exists,
            boolean identical, String content, boolean truncated) {
    }
}
