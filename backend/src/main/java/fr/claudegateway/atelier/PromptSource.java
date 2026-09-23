package fr.claudegateway.atelier;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * <b>Source des fichiers d'amorçage de la consigne système</b>, vue par la boucle d'atelier
 * (F-148 / SF-148-06).
 *
 * <p>Sur cible {@code RUNNER}, {@code buildSystemPrompt} relit à <b>chaque</b> message
 * {@code CLAUDE.md}, {@code STATE.md}/{@code PLAN-ACTION.md} du sujet, l'arborescence et les fichiers
 * de skills — jusqu'à ~19 allers-retours runner avant le premier mot du modèle. Cette source sert la
 * <b>copie rangée en base</b> (rapide) et planifie sa relecture <b>après</b> le tour.</p>
 *
 * <p><b>Repli passant.</b> {@link #NONE} rend la boucle d'avant F-148, à l'octet près : jamais
 * amorcé, tout est lu en direct par la boucle. C'est le comportement des tests qui ne branchent pas
 * le cache.</p>
 */
public interface PromptSource {

    /** Vrai si la copie de ce projet est amorcée : la consigne peut alors être servie depuis la base. */
    boolean isPrimed(UUID userId, Workspace workspace);

    /** Contenu rangé d'un fichier, ou vide (même sémantique que {@code readOptional}). */
    Optional<String> read(UUID userId, Workspace workspace, String path);

    /** L'arborescence rangée, ligne par ligne, ou une liste vide (même format que {@code safeTree}). */
    List<String> tree(UUID userId, Workspace workspace);

    /**
     * Planifie, hors du chemin critique, la relecture des sources de la consigne de ce projet.
     *
     * @param coreFiles       fichiers toujours relus ({@code CLAUDE.md}, {@code STATE.md}, {@code PLAN-ACTION.md})
     * @param skillPathFilter prédicat retenant les chemins de skills dans l'arborescence
     * @param maxSkills       plafond de skills rangés (aligné sur le catalogue annoncé)
     */
    void refreshAfterTurn(UUID userId, Workspace workspace, List<String> coreFiles,
            Predicate<String> skillPathFilter, int maxSkills);

    /** Repli inerte : jamais amorcé, aucune lecture, aucune relecture. */
    PromptSource NONE = new PromptSource() {
        @Override
        public boolean isPrimed(UUID userId, Workspace workspace) {
            return false;
        }

        @Override
        public Optional<String> read(UUID userId, Workspace workspace, String path) {
            return Optional.empty();
        }

        @Override
        public List<String> tree(UUID userId, Workspace workspace) {
            return List.of();
        }

        @Override
        public void refreshAfterTurn(UUID userId, Workspace workspace, List<String> coreFiles,
                Predicate<String> skillPathFilter, int maxSkills) {
            // Rien : la boucle lit tout en direct, comme avant F-148.
        }
    };
}
