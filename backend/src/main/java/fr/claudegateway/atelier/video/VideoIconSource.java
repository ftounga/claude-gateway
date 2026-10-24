package fr.claudegateway.atelier.video;

import java.util.List;

/**
 * La source des <b>icônes officielles</b> des cours vidéo (F-215 / SF-215-03) : AWS, Azure, GCP, Kubernetes, on-prem,
 * génériques — celles de la lib {@code diagrams} déjà installée pour F-142. Un nom d'icône est son chemin sous
 * {@code resources/}, sans extension : {@code aws/network/elb-application-load-balancer}.
 */
public interface VideoIconSource {

    /** Une icône lue : son nom et ses octets PNG. */
    record Icon(String name, byte[] png) {
    }

    /**
     * Le résultat d'une lecture.
     *
     * @param icons   les icônes trouvées
     * @param missing les noms valides mais introuvables
     * @param invalid les noms refusés (forme, fournisseur hors liste)
     */
    record Batch(List<Icon> icons, List<String> missing, List<String> invalid) {
    }

    /** Vrai si le service est configuré. */
    boolean isAvailable();

    /**
     * @return les noms d'icônes qui contiennent tous les mots de la recherche (40 au plus)
     * @throws fr.claudegateway.diagrams.DiagramRejectedException           recherche refusée
     * @throws fr.claudegateway.diagrams.DiagramRendererUnavailableException service absent ou muet
     */
    List<String> search(String query);

    /**
     * @param names 40 noms au plus
     * @throws fr.claudegateway.diagrams.DiagramRejectedException           demande refusée
     * @throws fr.claudegateway.diagrams.DiagramRendererUnavailableException service absent ou muet
     */
    Batch fetch(List<String> names);
}
