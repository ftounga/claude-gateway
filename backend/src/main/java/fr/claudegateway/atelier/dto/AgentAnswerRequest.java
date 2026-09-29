package fr.claudegateway.atelier.dto;

import java.util.List;

import fr.claudegateway.atelier.AtelierAnswerEntry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * Réponse de l'utilisateur à une <b>question structurée</b> de l'agent (F-164 / SF-164-01).
 *
 * <p>Elle désigne la question par son {@code callId} (celui relayé par l'événement
 * {@code question_request}) et porte une entrée par question du lot. Chaque entrée porte les options
 * cochées et/ou une réponse libre — l'option « autre » toujours acceptée ; une entrée sans l'un ni
 * l'autre est refusée (400) : un corps vide ne vaut pas réponse.</p>
 *
 * @param callId  identifiant de la question à trancher — obligatoire
 * @param answers une entrée par question posée (1 à 4) — obligatoire
 */
public record AgentAnswerRequest(
        @NotBlank @Size(max = 200) String callId,
        @NotEmpty @Size(max = 4) @Valid List<Answer> answers) {

    /**
     * Réponse à une question du lot.
     *
     * @param header   rappel d'intitulé pour le compte rendu lisible (facultatif)
     * @param selected libellés des options choisies (vide si réponse libre seule)
     * @param other    réponse libre saisie (« autre »)
     */
    public record Answer(
            @Size(max = 120) String header,
            @Size(max = 8) List<@Size(max = 200) String> selected,
            @Size(max = 2000) String other) {

        /**
         * Une réponse doit porter <b>au moins</b> un choix ou une réponse libre non vide.
         *
         * <p>Portée par {@code @AssertTrue} pour rendre un 400 propre à la validation, comme le reste
         * du contrat — plutôt qu'une exception métier plus loin.</p>
         */
        @AssertTrue(message = "Chaque réponse doit porter au moins une option ou un texte libre.")
        public boolean isAnswered() {
            boolean hasSelected = selected != null && selected.stream().anyMatch(s -> s != null && !s.isBlank());
            boolean hasOther = other != null && !other.isBlank();
            return hasSelected || hasOther;
        }
    }

    /** Traduit le corps en entrées de domaine, prêtes pour le compte rendu rendu au modèle. */
    public List<AtelierAnswerEntry> toEntries() {
        return answers.stream()
                .map(a -> new AtelierAnswerEntry(a.header(), a.selected(), a.other()))
                .toList();
    }
}
