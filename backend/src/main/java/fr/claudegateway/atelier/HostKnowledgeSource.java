package fr.claudegateway.atelier;

import java.util.UUID;

/**
 * D'où la boucle tire <b>ce qu'elle sait déjà du client</b>, et à qui elle signale qu'un tour vient
 * de finir (F-136 / SF-136-01, SF-136-02).
 *
 * <p><b>Une interface plutôt qu'une dépendance directe</b>, exactement comme {@link
 * ProjectRulesSource} l'a fait pour les règles de gouvernance : {@link AtelierChatService} n'a pas à
 * connaître le magasin de cartes pour composer sa consigne, et les tests de la boucle continuent de
 * se monter sans le module. {@link #NONE} rend le comportement d'avant F-136, à l'octet près.</p>
 */
public interface HostKnowledgeSource {

    /** Aucun savoir, jamais : la consigne système est celle d'avant F-136. */
    HostKnowledgeSource NONE = new HostKnowledgeSource() {

        @Override
        public String outlineFor(UUID userId, UUID workspaceId) {
            return null;
        }

        @Override
        public String factsFor(UUID userId, UUID workspaceId, String question) {
            return null;
        }

        @Override
        public void refreshAfterTurn(UUID userId, UUID workspaceId) {
            // Rien à rafraîchir.
        }
    };

    /**
     * Le <b>sommaire</b> de ce que la gateway sait du client de ce projet — déjà composé et borné.
     *
     * <p><b>Volontairement sans chiffre volatil</b> : ce bloc vit dans le préfixe stable du prompt,
     * et tout octet qui y change invalide le cache de <b>tout</b> ce qui suit (le défaut que F-134
     * vient de corriger, part relue 16-23 % → 99 %). Le nombre de faits et la date du dernier apport
     * changent à chaque tour : ils restent à l'écran, jamais ici.</p>
     *
     * @return le bloc, ou {@code null} s'il n'y a rien à dire
     */
    String outlineFor(UUID userId, UUID workspaceId);

    /**
     * Les <b>faits</b> de la carte de ce client qui portent sur ce que la question mentionne
     * (F-137 / SF-137-01) — déjà composés et bornés.
     *
     * <p><b>Destinés au message du tour, jamais à la consigne système</b> : ils dépendent de la
     * question, donc changent à chaque tour. Dans le préfixe, ils invalideraient le cache à chaque
     * demande ; dans le dernier message, ils n'invalident rien.</p>
     *
     * @return le bloc, ou {@code null} si la question ne cite rien de connu
     */
    String factsFor(UUID userId, UUID workspaceId, String question);

    /**
     * Signale qu'un tour vient de se terminer sur ce projet : la carte du poste peut avoir changé.
     *
     * <p><b>Ne doit jamais bloquer ni lever</b> : la réponse est déjà partie quand ceci est appelé.
     * L'implémentation travaille en arrière-plan.</p>
     */
    void refreshAfterTurn(UUID userId, UUID workspaceId);
}
