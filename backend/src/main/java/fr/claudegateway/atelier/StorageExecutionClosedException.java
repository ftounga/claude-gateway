package fr.claudegateway.atelier;

/**
 * La boucle maison a été sollicitée sur un projet <b>sans machine connectée</b> (F-39 / SF-39-16).
 *
 * <p>Ce chemin — exécuter les outils sur le stockage objet — n'a plus d'usage produit depuis que
 * l'écran résout lui-même son moteur (lot 4) : un projet sans runner passe par les Managed Agents.
 * Il reste ouvrable par le coupe-circuit {@code app.atelier.storage-execution}, fermé par défaut.</p>
 *
 * <p>Mappé en {@code 409 storage_execution_closed} : c'est un conflit avec l'état du projet, pas une
 * panne. Le message dit <b>où</b> le travail se fait, pas ce qui a échoué.</p>
 */
public class StorageExecutionClosedException extends RuntimeException {

    public StorageExecutionClosedException(String message) {
        super(message);
    }
}
