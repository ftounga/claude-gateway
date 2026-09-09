package fr.claudegateway.runner;

/**
 * Fournit les outils <b>bornés au projet</b> d'un appel (F-48 / SF-48-02).
 *
 * <p>C'est le point qui fait tenir le régime de confinement retenu par le cadrage : le runner est
 * lancé à la racine du <b>poste</b>, mais chaque appel dit sur quel <b>projet</b> il travaille, et
 * c'est ce processus-ci — pas la gateway — qui referme la garde dessus. La gateway indique ; le
 * runner refuse.</p>
 *
 * <p>Un port, et non une classe concrète, pour une raison unique : l'aiguilleur de trames doit rester
 * vérifiable sans arborescence réelle. La mise en œuvre est {@link ProjectScopes}.</p>
 */
@FunctionalInterface
public interface ToolScopes {

    /**
     * Outils confinés à ce projet.
     *
     * @param project chemin relatif à la racine du poste ; {@code null} ou vide = la racine
     * @throws ToolException {@code path_outside_root} si le chemin sort de la racine,
     *                       {@code not_found} si le dossier n'existe pas,
     *                       {@code invalid_input} s'il est malformé
     */
    ToolExecutor forProject(String project);

    /**
     * Portée <b>unique</b> : les mêmes outils quel que soit le projet demandé. Réservé aux tests et
     * aux chemins de montage historiques — en service, c'est toujours {@link ProjectScopes} qui
     * répond, et c'est lui qui borne.
     */
    static ToolScopes fixed(ToolExecutor tools) {
        return project -> tools;
    }
}
