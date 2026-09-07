package fr.claudegateway.atelier;

import java.util.UUID;

/**
 * Enregistrement du genre d'interpréteur déclaré par un runner (F-38 / SF-38-27).
 *
 * <p>Port volontairement <b>étroit</b> : l'aiguilleur de trames du canal runner n'a besoin que de
 * cela du service des projets, et rien ne justifie qu'un composant de protocole voie toute la
 * surface de {@link WorkspaceService}. Il rend aussi le comportement vérifiable sans base de
 * données — c'est une lambda dans les tests de l'aiguilleur.</p>
 */
@FunctionalInterface
public interface RunnerShellRecorder {

    /**
     * Enregistre le genre déclaré pour ce projet.
     *
     * <p>L'identifiant vient <b>toujours</b> de la session runner authentifiée, jamais d'un champ du
     * message : une trame ne peut pas écrire sur le projet d'un autre.</p>
     *
     * @param workspaceId projet porté par la session runner
     * @param declared    valeur du champ {@code shell} de la trame {@code ready} ; hors liste
     *                    blanche, nulle ou vide, elle est ignorée
     */
    void recordRunnerShell(UUID workspaceId, String declared);
}
