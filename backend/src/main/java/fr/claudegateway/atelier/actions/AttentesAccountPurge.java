package fr.claudegateway.atelier.actions;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>La purge des données F-180</b> : interlocuteurs (SF-180-03) et registre des accès (SF-180-04), à la
 * suppression du compte — et le registre d'un terminal hébergé supprimé, qui n'aurait plus de portée.
 * Pas de clé étrangère : purge nommée, sinon elles survivraient.
 */
@Component
public class AttentesAccountPurge {

    private final InterlocuteurRepository interlocuteurs;
    private final AccesObtenuRepository acces;

    public AttentesAccountPurge(InterlocuteurRepository interlocuteurs, AccesObtenuRepository acces) {
        this.interlocuteurs = interlocuteurs;
        this.acces = acces;
    }

    @Transactional
    public void purgeUser(UUID userId) {
        interlocuteurs.purgeUser(userId);
        acces.purgeUser(userId);
    }

    /** Le registre d'un terminal hébergé supprimé (celui d'un poste reste au poste). */
    @Transactional
    public void purgeHostedWorkspace(UUID userId, UUID workspaceId) {
        acces.purgeHostedWorkspace(userId, workspaceId);
    }
}
