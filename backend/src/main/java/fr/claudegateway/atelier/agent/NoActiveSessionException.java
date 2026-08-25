package fr.claudegateway.atelier.agent;

/**
 * Aucune session sandbox en cours pour ce workspace (F-31 / SF-31-04).
 *
 * <p>Levée par les opérations qui n'ont de sens que sur un travail <b>déjà fait</b> — la publication
 * sur une branche au premier chef. Ouvrir une session pour l'occasion repartirait d'un clone vierge
 * et pousserait une branche identique à la base : un succès trompeur, et du temps de sandbox facturé
 * pour rien.</p>
 */
public class NoActiveSessionException extends RuntimeException {

    public NoActiveSessionException(String message) {
        super(message);
    }
}
