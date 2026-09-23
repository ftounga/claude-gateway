package fr.claudegateway.atelier;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <b>Mémoire de résolutions</b> par poste, vue par la boucle d'atelier (F-148 / SF-148-08).
 *
 * <p>Propose une résolution déjà trouvée sur une question similaire (rappel dans le MESSAGE, patron
 * F-137) et enregistre la résolution d'un tour abouti. {@link #NONE} rend la boucle d'avant F-148 :
 * aucun rappel, aucun enregistrement.</p>
 */
public interface ResolutionMemory {

    /**
     * Bloc « déjà résolu » à préfixer à la consigne du tour, ou {@code Optional.empty()}. Encadré
     * comme une donnée à vérifier (anti-injection). Vit dans le message, jamais dans la consigne
     * système (cache F-134 préservé).
     */
    Optional<String> recall(UUID userId, Workspace workspace, String question);

    /**
     * Planifie, hors du chemin critique, l'enregistrement de la résolution d'un tour <b>abouti</b>.
     *
     * @param question   la parole de l'utilisateur
     * @param conclusion la réponse du tour
     * @param files      chemins des fichiers touchés pendant le tour
     */
    void recordAfterTurn(UUID userId, Workspace workspace, String question, String conclusion,
            List<String> files);

    /** Repli inerte : aucun rappel, aucun enregistrement. */
    ResolutionMemory NONE = new ResolutionMemory() {
        @Override
        public Optional<String> recall(UUID userId, Workspace workspace, String question) {
            return Optional.empty();
        }

        @Override
        public void recordAfterTurn(UUID userId, Workspace workspace, String question,
                String conclusion, List<String> files) {
            // Rien : la boucle est celle d'avant F-148.
        }
    };
}
