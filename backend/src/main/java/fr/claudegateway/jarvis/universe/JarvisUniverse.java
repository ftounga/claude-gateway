package fr.claudegateway.jarvis.universe;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * <b>L'univers de Jarvis</b> (F-234 / SF-234-01, D4) : ce que l'écran {@code /jarvis} dessine autour de l'orbe —
 * clients (postes) → sujets → chantiers ouverts → étape courante, plus les interlocuteurs (pour reconnaître un nom
 * dans ce qui s'écrit) et les destinataires des messages « À envoyer ».
 *
 * <p><b>Aucun contenu</b> : ni message, ni corps, ni sortie de commande, ni description d'attente — des noms, des
 * numéros et des compteurs seulement. Jamais d'identifiant de compte.</p>
 *
 * @param clients     au plus {@value JarvisUniverseService#MAX_CLIENTS} clients, connectés d'abord
 * @param toSendCount le nombre total d'encarts pas encore marqués envoyés (tous clients confondus)
 */
public record JarvisUniverse(OffsetDateTime generatedAt, List<Client> clients, int toSendCount) {

    /**
     * Un client = un poste ({@code id} nul pour « Hébergé », qui n'est pas une machine).
     *
     * @param missionStatus l'état de mission déclaré (F-60), {@code null} pour « Hébergé »
     * @param subjects      au plus {@value JarvisUniverseService#MAX_SUBJECTS} sujets, terminal du poste d'abord
     * @param people        les interlocuteurs connus du poste (F-180)
     * @param toSend        les destinataires distincts des encarts à envoyer de ce client
     * @param truncated     vrai si une borne a coupé quelque chose sur ce client
     */
    public record Client(UUID id, String name, boolean virtual, boolean connected, String missionStatus,
                         List<Subject> subjects, List<Person> people, List<String> toSend, boolean truncated) {
    }

    /**
     * Un sujet (workspace).
     *
     * @param hostTerminal vrai pour le terminal du poste (F-74)
     * @param live         un terminal vivant est ouvert sur ce sujet (F-70)
     */
    public record Subject(UUID id, String name, boolean hostTerminal, boolean live, List<Chantier> chantiers) {
    }

    /**
     * Un chantier ouvert.
     *
     * @param step      l'étape courante (la première ni faite ni vérifiée), ou {@code null}
     * @param waitingOn les personnes qui doivent agir pour qu'il avance (noms, jamais la description)
     */
    public record Chantier(int number, String title, String phase, String phaseLabel, int done, int total,
                           Step step, boolean focus, boolean live, List<String> waitingOn) {
    }

    /** Une étape : son rang et son titre. */
    public record Step(int rank, String title) {
    }

    /** Un interlocuteur : son nom affiché, ses autres formes reconnues (normalisées), son rôle. */
    public record Person(String name, List<String> variants, String role) {
    }
}
