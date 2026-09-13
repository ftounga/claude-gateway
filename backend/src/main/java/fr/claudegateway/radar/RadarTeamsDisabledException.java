package fr.claudegateway.radar;

/**
 * Le volet Teams n'est pas actif sur le poste (F-100) : capacité non annoncée ({@code --no-teams}) ou
 * runner trop ancien pour les appels du Radar. 409.
 */
public class RadarTeamsDisabledException extends RuntimeException {

    public RadarTeamsDisabledException(String message) {
        super(message);
    }
}
