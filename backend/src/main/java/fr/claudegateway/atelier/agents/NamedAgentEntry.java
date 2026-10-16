package fr.claudegateway.atelier.agents;

import java.util.List;

/**
 * Un agent nommé tel que l'écran le montre (F-182 / SF-182-01) : <b>sans sa consigne</b>, qui reste chez
 * le client et ne transite que vers le modèle au moment d'une délégation.
 *
 * @param name        nom (à taper après {@code /agent})
 * @param path        fichier, relatif à la racine de son origine
 * @param origin      {@code SUJET} | {@code POSTE}
 * @param description ce qu'il fait
 * @param access      {@code LECTURE} | {@code ECRITURE}
 * @param tools       outils retenus (nos noms)
 * @param model       modèle écrit et justifié, ou {@code null} = modèle de la Forge
 * @param warnings    ce qui a été ignoré, en clair
 * @param errors      ce qui le rend inutilisable, en clair
 * @param usable      vrai si délégable
 */
public record NamedAgentEntry(String name, String path, String origin, String description, String access,
        List<String> tools, String model, List<String> warnings, List<String> errors, boolean usable) {
}
