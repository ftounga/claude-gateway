package fr.claudegateway.atelier;

import java.util.Optional;
import java.util.UUID;

/**
 * <b>Index de repo persistant</b>, vu par la boucle d'atelier (F-148 / SF-148-07).
 *
 * <p>Sert l'outil {@code glob} depuis la base (aide de localisation) quand c'est sûr, et planifie la
 * relecture de l'index <b>après</b> le tour. {@link #NONE} rend la boucle d'avant F-148 : jamais
 * amorcé, {@code glob} toujours en direct.</p>
 */
public interface RepoIndex {

    /** Vrai si l'index de ce projet est amorcé : {@code glob} peut alors être servi depuis la base. */
    boolean isPrimed(UUID userId, Workspace workspace);

    /**
     * Évalue un motif {@code glob} sur l'index, ou {@code Optional.empty()} si l'index ne peut pas
     * répondre (non amorcé, motif invalide, panne). Un résultat présent — même vide — vient de
     * l'index. L'appelant ne l'utilise que lorsque c'est sûr (amorcé + aucune mutation du tour).
     *
     * @param base sous-dossier de base ({@code path} de l'outil), ou vide/null pour tout le projet
     */
    Optional<String> glob(UUID userId, Workspace workspace, String pattern, String base);

    /** Planifie, hors du chemin critique, la relecture de l'index de ce projet. */
    void refreshAfterTurn(UUID userId, Workspace workspace);

    /** Repli inerte : jamais amorcé, jamais servi, aucune relecture. */
    RepoIndex NONE = new RepoIndex() {
        @Override
        public boolean isPrimed(UUID userId, Workspace workspace) {
            return false;
        }

        @Override
        public Optional<String> glob(UUID userId, Workspace workspace, String pattern, String base) {
            return Optional.empty();
        }

        @Override
        public void refreshAfterTurn(UUID userId, Workspace workspace) {
            // Rien : glob reste en direct, comme avant F-148.
        }
    };
}
