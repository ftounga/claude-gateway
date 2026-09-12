package fr.claudegateway.runner.host;

import java.util.UUID;

/**
 * Enregistrement de la version que le runner d'un poste <b>déclare</b> (F-81 / SF-81-03).
 *
 * <p>Port volontairement <b>étroit</b>, à l'image de {@link RunnerShellRecorder} : l'aiguilleur de
 * trames du canal runner n'a besoin que de cela du service des postes, et rien ne justifie qu'un
 * composant de protocole voie toute la surface de {@link RunnerHostService}. Il rend aussi le
 * comportement vérifiable sans base de données — c'est une lambda dans les tests de l'aiguilleur.</p>
 */
@FunctionalInterface
public interface RunnerVersionRecorder {

    /**
     * Retient la version déclarée pour ce poste.
     *
     * <p>L'identifiant vient <b>toujours</b> de la session runner authentifiée, jamais d'un champ du
     * message : une trame ne peut pas écrire sur le poste d'un autre.</p>
     *
     * <p>Cette valeur ne <b>décide</b> de rien. Elle est retenue pour être affichée et journalisée ;
     * aucun runner n'est refusé, dégradé ou arrêté sur ce qu'elle vaut.</p>
     *
     * @param hostId   poste porté par la session runner
     * @param declared valeur du champ {@code runnerVersion} de la trame {@code ready} ; nulle, vide
     *                 ou trop longue, elle est ignorée
     */
    void recordRunnerVersion(UUID hostId, String declared);
}
