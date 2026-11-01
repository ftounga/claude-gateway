package fr.claudegateway.atelier.situation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Le point de situation d'un terminal (F-225 / SF-225-01), tel que l'écran le montre.
 *
 * @param generatedAt    l'instant de la lecture
 * @param lastActivityAt    le dernier message du fil (tous rôles), ou {@code null} — « Dernier échange il y a… »
 * @param idleHours         heures entières depuis le dernier message <b>de l'utilisateur</b> (SF-225-03), ou
 *                          {@code null} ; l'écran le croise avec la dernière visite de l'appareil
 * @param mode              {@code LIBRE} ou {@code GUIDE}
 * @param exchange          la dernière demande et la dernière réponse (l'essentiel préservé à la coupe), ou {@code null}
 * @param lastUserMessageAt le dernier message écrit par l'utilisateur — jamais une demande automatique d'une course
 *                          autonome ou d'une routine (SF-225-03), ou {@code null}
 * @param turnLive          un tour tourne dans le fil que l'écran principal montre (fil commun ou chantier au
 *                          focus, ici ou chez un pair, course autonome comprise) : la carte ne s'ouvre pas d'elle-même
 */
public record Situation(OffsetDateTime generatedAt, OffsetDateTime lastActivityAt, Long idleHours, String mode,
                        List<ChantierLine> chantiers, List<Attente> attentes, List<Envoi> envois, Exchange exchange,
                        List<Piece> pieces, List<Suite> suites, OffsetDateTime lastUserMessageAt,
                        boolean turnLive) {

    /**
     * Un chantier ouvert et l'étape où il en est.
     *
     * @param nextRank  le rang de l'étape suivante (ni faite ni vérifiée), ou {@code null}
     * @param who       qui doit agir (« L'outil peut avancer », « Ça attend votre décision »…)
     */
    public record ChantierLine(int number, String title, String phaseLabel, boolean focus, int done, int total,
                               Integer nextRank, String nextStep, boolean planAwaitingValidation,
                               boolean diagnosisPending, String who, String whoDetail, boolean stale) {
    }

    /** Une attente ouverte : qui, à qui, depuis quand. */
    public record Attente(UUID id, String description, String status, String qui, String to, OffsetDateTime since,
                          Integer chantierNumber) {
    }

    /**
     * Un encart mail ou message proposé par l'agent ces 7 derniers jours.
     *
     * @param sentAt        marqué envoyé (F-211), ou {@code null} : « pas marqué envoyé »
     * @param attenteStatus l'état de l'attente liée ({@code A_FAIRE}, {@code DEMANDE}…), ou {@code null}
     * @param excerpt       le début du texte proposé (400 caractères au plus), pour [Voir]
     * @param chantierNumber le fil (chantier) du message qui l'a proposé, ou {@code null} : fil commun (SF-225-03)
     */
    public record Envoi(UUID messageId, int blockIndex, String kind, String subject, String recipient,
                        OffsetDateTime proposedAt, OffsetDateTime sentAt, String attenteKey, String attenteStatus,
                        String excerpt, Integer chantierNumber) {
    }

    /** La dernière demande de l'utilisateur et la dernière réponse. */
    public record Exchange(String request, OffsetDateTime requestAt, String reply, OffsetDateTime replyAt) {
    }

    /** Une pièce fournie (dépôt F-169), la plus récente d'abord. */
    public record Piece(UUID id, String path, long sizeBytes, OffsetDateTime createdAt, UUID messageId) {
    }

    /** Une suite proposée par la dernière réponse (F-192). */
    public record Suite(String title, String request) {
    }
}
