package fr.claudegateway.atelier;

import java.util.UUID;

/**
 * <b>Le bloc de passation</b> (F-179 / SF-179-01, décision D1) : ce que le terminal du poste pose dans
 * le fil quand l'utilisateur a dit « go » pour passer à un sujet — le sujet à ouvrir et la phrase de
 * démarrage à <b>déposer</b> (jamais envoyer) dans sa saisie.
 *
 * <p>Relayé au fil de l'eau (événement {@code handoff}) et rangé dans la transcription du tour : la
 * carte [Ouvrir le sujet] survit au rechargement. Le sujet a été résolu par la gateway, isolé
 * {@code user_id} + {@code host_id} ; jamais d'identifiant de compte.</p>
 *
 * @param workspaceId le sujet (projet du même poste) à ouvrir
 * @param name        son nom affiché
 * @param phrase      la phrase de démarrage, déposée non envoyée
 */
public record SubjectHandoff(UUID workspaceId, String name, String phrase) {

    /** Borne de la phrase : une phrase de démarrage, pas un document. */
    public static final int MAX_PHRASE_CHARS = 2_000;
}
