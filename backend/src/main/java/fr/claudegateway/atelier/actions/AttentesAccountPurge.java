package fr.claudegateway.atelier.actions;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>La purge des données F-180 à la suppression du compte</b> : interlocuteurs (SF-180-03) et registre
 * des accès (SF-180-04). Pas de clé étrangère : purge nommée, sinon elles survivraient au compte.
 */
@Component
public class AttentesAccountPurge {

    private final InterlocuteurRepository interlocuteurs;

    public AttentesAccountPurge(InterlocuteurRepository interlocuteurs) {
        this.interlocuteurs = interlocuteurs;
    }

    @Transactional
    public void purgeUser(UUID userId) {
        interlocuteurs.purgeUser(userId);
    }
}
