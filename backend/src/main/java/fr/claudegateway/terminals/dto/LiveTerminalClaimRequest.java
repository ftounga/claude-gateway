package fr.claudegateway.terminals.dto;

import java.util.List;

import fr.claudegateway.terminals.LiveTerminal;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Prise ou renouvellement d'une place de terminal vivant (F-70 / SF-70-01), et — depuis F-76 /
 * SF-76-01 — <b>ce que ce terminal est en train de faire</b>.
 *
 * <p>Le {@code sessionId} identifie un <b>onglet</b>, pas un utilisateur : il est toujours lu avec
 * le {@code user_id} du jeton, ne donne accès à rien par lui-même, et n'est jamais journalisé. Son
 * alphabet est volontairement étroit — il finit dans des requêtes et des journaux, et rien n'exige
 * qu'il accepte autre chose qu'un UUID.</p>
 *
 * <p><b>Pourquoi l'aperçu voyage ici</b> plutôt que sur un endpoint à lui : le battement de cœur
 * part déjà, toutes les trente secondes, depuis l'onglet qui travaille. Lui faire porter l'aperçu
 * ne coûte ni une connexion, ni un aller-retour, ni une ligne de base — et il ne peut pas y avoir
 * d'aperçu sans place tenue, puisque c'est la même fiche.</p>
 *
 * <p><b>Les trois champs d'aperçu sont facultatifs</b>, et leur absence <b>n'efface rien</b> : un
 * écran antérieur à F-76 continue de tenir sa place, exactement comme avant. Pour dire « il ne se
 * passe plus rien », on envoie {@code activity: "IDLE"} — le silence n'est pas une affirmation.</p>
 *
 * @param sessionId      identifiant d'onglet, obligatoire
 * @param activity       étiquette d'activité ({@code IDLE}, {@code THINKING}, {@code RUNNING},
 *                       {@code AWAITING_APPROVAL}) ; une valeur hors vocabulaire est lue
 *                       {@code IDLE} plutôt que refusée
 * @param activityDetail ce qui est en cours (« npm test »), tronqué au serveur
 * @param previewLines   les dernières lignes du terminal, bornées et nettoyées au serveur
 */
public record LiveTerminalClaimRequest(
        @NotBlank(message = "L'identifiant d'onglet est obligatoire")
        @Size(max = LiveTerminal.MAX_SESSION_ID_LENGTH,
                message = "L'identifiant d'onglet est trop long")
        @Pattern(regexp = "[A-Za-z0-9_-]+",
                message = "L'identifiant d'onglet contient un caractère non autorisé")
        String sessionId,

        // Bornée ET filtrée : au-delà de 24 caractères ou hors de cet alphabet, ce n'est plus une
        // étiquette d'activité mais un texte libre, et la colonne n'est pas faite pour ça. Une
        // valeur BIEN FORMÉE mais inconnue, elle, passe : c'est le cas d'un écran en avance sur sa
        // gateway, et lui refuser son battement de cœur lui coûterait sa place pour un ornement.
        @Size(max = 24, message = "L'étiquette d'activité est trop longue")
        @Pattern(regexp = "[A-Z_]*", message = "L'étiquette d'activité n'est pas reconnue")
        String activity,

        String activityDetail,

        List<String> previewLines) {

    /** Vrai si l'appel porte un aperçu — sinon la fiche garde le sien, elle ne l'oublie pas. */
    public boolean hasPreview() {
        return activity != null || activityDetail != null || previewLines != null;
    }
}
