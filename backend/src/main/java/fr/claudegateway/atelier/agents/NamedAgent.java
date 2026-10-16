package fr.claudegateway.atelier.agents;

import java.util.List;

/**
 * Un <b>agent nommé</b> (F-182 / SF-182-01, décision D1) : un sous-agent spécialisé et réutilisable,
 * déclaré par le client dans {@code .claude/agents/<nom>.md} (sujet, puis poste) — sa consigne, ses
 * outils autorisés et, s'il le justifie, son modèle.
 *
 * @param name          le nom (celui du fichier), à passer à {@code explore}/{@code task} ou après {@code /agent}
 * @param path          son fichier, relatif à la racine de son origine
 * @param origin        {@code SUJET} ou {@code POSTE}
 * @param description   ce qu'il fait (en-tête {@code description:}, sinon 1re ligne de la consigne), bornée
 * @param access        {@code LECTURE} ou {@code ECRITURE} — déduit des outils retenus
 * @param tools         les outils retenus, sous NOS noms ({@code read_file}, {@code grep}…), dans un ordre stable
 * @param model         le modèle écrit ET justifié ; {@code null} = le modèle de la Forge (D3)
 * @param justification la justification du modèle, telle qu'écrite (ou {@code null})
 * @param instructions  la consigne de l'agent (le corps du fichier), bornée — jamais journalisée
 * @param warnings      ce qui a été ignoré ou corrigé, en clair (l'agent reste utilisable)
 * @param errors        ce qui le rend inutilisable, en clair (vide = utilisable)
 */
public record NamedAgent(String name, String path, String origin, String description, String access,
        List<String> tools, String model, String justification, String instructions, List<String> warnings,
        List<String> errors) {

    public static final String SUJET = "SUJET";
    public static final String POSTE = "POSTE";
    public static final String LECTURE = "LECTURE";
    public static final String ECRITURE = "ECRITURE";

    public NamedAgent {
        tools = tools == null ? List.of() : List.copyOf(tools);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    /** Vrai si l'agent peut être annoncé et délégué. */
    public boolean usable() {
        return errors.isEmpty();
    }

    /** Vrai si l'agent a au moins un outil d'écriture ou d'exécution. */
    public boolean writes() {
        return ECRITURE.equals(access);
    }

    /** La vue publique (sans consigne). */
    public NamedAgentEntry entry() {
        return new NamedAgentEntry(name, path, origin, description, access, tools, model, warnings, errors,
                usable());
    }
}
