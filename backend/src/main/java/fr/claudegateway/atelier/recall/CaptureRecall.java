package fr.claudegateway.atelier.recall;

import java.util.UUID;

/**
 * <b>Les captures de conversations du sujet, pour {@code recall}</b> (F-247 / SF-247-13, D14) : les transcriptions
 * vivent dans leur propre table (jamais dans {@code atelier_messages}) ; {@code recall} les interroge <b>en union</b>,
 * sous {@code user_id} + {@code workspace_id}, avec les mêmes bornes et le même repli sur les mots-clés.
 */
public interface CaptureRecall {

    /**
     * Les extraits de captures du sujet qui répondent à {@code needle}, déjà mis en forme (une ligne chacun, datée par
     * la passerelle), ou une chaîne vide. Jamais d'exception.
     */
    String extracts(UUID userId, UUID workspaceId, String needle);
}
