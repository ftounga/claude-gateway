package fr.claudegateway.atelier;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Plan de travail que l'agent pose et tient à jour pendant un tour (F-39 / SF-39-13).
 *
 * <p>Deux manques que le même objet comble. Pour l'<b>agent</b> : un plan écrit dans le contexte est
 * un engagement qu'il relit à chaque itération, là où rien ne l'obligeait à en formuler un. Pour
 * l'<b>utilisateur</b> : la ligne vivante (SF-30-13) dit ce qui se passe à l'instant, jamais ce qui
 * reste — sur un tour de trente étapes, c'est la différence entre patienter et se demander si
 * quelque chose est bloqué.</p>
 *
 * <p><b>Le plan se remplace, il ne se fusionne jamais</b> (décision D1). Une mise à jour partielle
 * supposerait que les deux côtés s'accordent sur une numérotation, et ferait diverger le plan de
 * l'agent de celui de l'écran dès le premier décalage.</p>
 *
 * <p><b>Aucun plan mal formé ne fait échouer un tour</b> (D2) : tout est normalisé, jamais refusé.
 * Un outil d'organisation qui casse le travail qu'il organise serait pire que son absence.</p>
 */
public record AtelierPlan(List<Step> steps) {

    /** Au-delà, ce n'est plus un plan mais une transcription — et il est renvoyé à chaque itération. */
    public static final int MAX_STEPS = 20;

    /** Une ligne d'écran. */
    public static final int MAX_TITLE_CHARS = 200;

    /** Plan vide : l'agent n'en a pas posé, ou vient de l'effacer. */
    public static final AtelierPlan EMPTY = new AtelierPlan(List.of());

    /** État d'une étape. Une seule peut être {@link #ACTIVE} à la fois. */
    public enum Status {
        PENDING, ACTIVE, DONE;

        /** État reçu du modèle, ou {@link #PENDING} s'il est inconnu — jamais un refus. */
        static Status parse(String raw) {
            if (raw == null) {
                return PENDING;
            }
            return switch (raw.trim().toLowerCase()) {
                case "active", "in_progress", "doing" -> ACTIVE;
                case "done", "completed", "finished" -> DONE;
                default -> PENDING;
            };
        }

        /** Valeur rendue à l'écran et au modèle, en minuscules. */
        public String label() {
            return name().toLowerCase();
        }
    }

    /** Une étape du plan : ce qu'il y a à faire, et où on en est. */
    public record Step(String title, Status status) {
    }

    /** Vrai si le plan ne porte aucune étape. */
    public boolean isEmpty() {
        return steps.isEmpty();
    }

    /**
     * Construit un plan à partir de ce que le modèle a envoyé, en normalisant tout ce qui peut
     * l'être : titres élagués et tronqués, entrées vides ignorées, états inconnus ramenés à
     * {@code pending}, une seule étape active, et au plus {@link #MAX_STEPS} étapes.
     *
     * @param stepsNode nœud {@code steps} de l'appel d'outil ({@code null} ou vide ⇒ plan effacé)
     * @return le plan normalisé, jamais {@code null}
     */
    public static AtelierPlan from(JsonNode stepsNode) {
        if (stepsNode == null || !stepsNode.isArray()) {
            return EMPTY;
        }
        List<Step> parsed = new ArrayList<>();
        boolean activeSeen = false;
        for (JsonNode node : stepsNode) {
            if (parsed.size() >= MAX_STEPS) {
                break;
            }
            String title = node.path("title").asText("").trim();
            if (title.isEmpty()) {
                continue; // Une étape sans intitulé n'apprend rien à personne.
            }
            if (title.length() > MAX_TITLE_CHARS) {
                title = title.substring(0, MAX_TITLE_CHARS);
            }
            Status status = Status.parse(node.path("status").asText(null));
            if (status == Status.ACTIVE) {
                // Deux étapes en cours n'ont pas de sens : la première l'emporte, les suivantes
                // retombent en attente. On normalise plutôt que de refuser (D2).
                if (activeSeen) {
                    status = Status.PENDING;
                } else {
                    activeSeen = true;
                }
            }
            parsed.add(new Step(title, status));
        }
        return new AtelierPlan(List.copyOf(parsed));
    }

    /**
     * Compte rendu destiné au <b>modèle</b> : ce qui a été retenu, et ce qui a été écarté. Le dire
     * lui permet de se corriger de lui-même, plutôt que d'ignorer une troncature qu'il ne verrait
     * jamais.
     *
     * @param submitted nombre d'entrées envoyées par le modèle
     */
    public String acknowledgement(int submitted) {
        if (steps.isEmpty()) {
            return "Plan effacé.";
        }
        String base = "Plan enregistré : " + steps.size() + " étape(s).";
        return submitted > MAX_STEPS
                ? base + " Seules les " + MAX_STEPS + " premières ont été retenues (limite du plan)."
                : base;
    }
}
