package fr.claudegateway.mail;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.billing.EntitlementSpace;
import fr.claudegateway.billing.SpaceEntitlementService;

/**
 * <b>L'outil {@code email_me} — et la garde qui décide s'il est donné</b> (F-110 / SF-110-02).
 *
 * <h2>On s'écrit à soi, jamais à un tiers</h2>
 *
 * <p>Le schéma n'a <b>aucun champ destinataire</b>, et l'exécution ne lit que {@code subject} et {@code body} :
 * un {@code to} glissé par le modèle est ignoré. Le destinataire est résolu par la gateway depuis le <b>poste du
 * terminal</b> ({@link HostMailAddressService#resolveRecipient}) : l'adresse vérifiée du client, sinon l'adresse
 * du compte — et la description de l'outil le <b>dit</b>, pour que l'agent l'annonce avant d'envoyer.</p>
 *
 * <h2>La garde</h2>
 *
 * <p>Même doctrine que les catalogues Teams et Radar : sans poste ou sans droit (Forge ou Vigie, administrateur
 * d'office), l'outil n'est pas donné — l'agent n'a pas la capacité. Appelé quand même, il est refusé.</p>
 *
 * <h2>Ce que l'outil refuse</h2>
 *
 * <p>Un objet ou un corps invalide, un secret manifeste ({@link ClientMailSecrets}), et le 51ᵉ courriel du compte
 * sur 24 heures glissantes. Rien n'est mis en file quand il refuse.</p>
 */
@Component
public class ClientMailTool {

    public static final String NAME = "email_me";

    /** Limite de courriels demandés par l'agent, par compte, sur {@link #LIMIT_WINDOW}. */
    public static final int DAILY_LIMIT = 50;
    static final Duration LIMIT_WINDOW = Duration.ofHours(24);
    static final int MAX_SUBJECT_CHARS = 200;
    static final int MAX_BODY_CHARS = 100_000;

    /** La règle des transcriptions bloquées (F-87 §9 bis), portée par la consigne de l'outil. */
    static final String TRANSCRIPT_RULE = "Si une transcription de réunion a été lue avec « downloadBlocked » à "
            + "vrai, ne la recopie JAMAIS dans un courriel : un résumé oui, la transcription brute non.";

    private final SpaceEntitlementService entitlements;
    private final HostMailAddressService addresses;
    private final ClientMailOutbox outbox;
    private final ClientEmailRepository emails;
    private final Clock clock;

    public ClientMailTool(SpaceEntitlementService entitlements, HostMailAddressService addresses,
            ClientMailOutbox outbox, ClientEmailRepository emails, Clock clock) {
        this.entitlements = entitlements;
        this.addresses = addresses;
        this.outbox = outbox;
        this.emails = emails;
        this.clock = clock;
    }

    /** Vrai si ce nom d'outil est {@code email_me}. */
    public static boolean isEmailTool(String name) {
        return NAME.equals(name);
    }

    /**
     * Vrai si l'outil est ouvert pour ce tour : un terminal <b>de poste</b> et le droit Forge ou Vigie.
     *
     * @param userId    propriétaire du terminal (celui du tour, jamais un paramètre client)
     * @param workspace terminal du tour, déjà vérifié possédé
     */
    public boolean isOpenFor(UUID userId, Workspace workspace) {
        if (userId == null || workspace == null || workspace.getHostId() == null || !workspace.isRunnerTarget()) {
            return false;
        }
        return entitlements.isEntitled(userId, EntitlementSpace.FORGE)
                || entitlements.isEntitled(userId, EntitlementSpace.VIGIE);
    }

    /** L'outil à donner pour ce tour, destinataire nommé dans sa description — ou rien. */
    public Optional<AgentTool> toolFor(UUID userId, Workspace workspace) {
        if (!isOpenFor(userId, workspace)) {
            return Optional.empty();
        }
        ResolvedRecipient recipient;
        try {
            recipient = addresses.resolveRecipient(userId, workspace.getHostId());
        } catch (RuntimeException e) {
            return Optional.empty(); // Poste introuvable : pas de client, pas d'outil.
        }
        if (recipient.address() == null) {
            return Optional.empty();
        }
        return Optional.of(definition(recipient));
    }

    /** La définition, destinataire écrit. Package-privé pour les tests. */
    static AgentTool definition(ResolvedRecipient recipient) {
        String to = recipient.verifiedForClient()
                ? "Destinataire (résolu par la gateway, non modifiable) : " + recipient.address()
                        + ", adresse vérifiée du client « " + recipient.clientName() + " »."
                : "Aucune adresse vérifiée pour « " + recipient.clientName() + " » : les courriels partent à "
                        + "l'adresse du compte, " + recipient.address() + ". Dis-le à l'utilisateur en une phrase "
                        + "AVANT d'envoyer.";
        Map<String, Object> text = Map.of("type", "string");
        return new AgentTool(NAME,
                "Envoie un courriel À L'UTILISATEUR LUI-MÊME — jamais à un tiers : compte rendu, procédure, "
                        + "brouillon de relance qu'il transmettra lui-même depuis sa boîte. " + to + " Il n'existe "
                        + "aucun moyen d'écrire à une autre adresse : ne propose jamais d'envoyer à quelqu'un d'autre. "
                        + "Corps en Markdown (titres, listes, tableaux), rendu en HTML sobre. Aucune confirmation "
                        + "n'est demandée ; l'envoi part en tâche de fond et son état s'affiche dans le terminal. "
                        + "N'y mets JAMAIS de secret (mot de passe, jeton, clé) : l'outil refuse. " + TRANSCRIPT_RULE
                        + " Limite : " + DAILY_LIMIT + " courriels par jour.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "subject", Map.of("type", "string",
                                        "description", "Objet, une ligne, " + MAX_SUBJECT_CHARS + " caractères au plus."),
                                "body", Map.of("type", "string",
                                        "description", "Corps en Markdown.")),
                        "required", List.of("subject", "body"),
                        "additionalProperties", false));
    }

    /** Le résultat d'un appel : ce que lit le modèle, et le reçu du bloc quand un courriel est en file. */
    public record Outcome(String content, boolean error, ClientMailReceipt receipt) {

        static Outcome refused(String content) {
            return new Outcome(content, true, null);
        }
    }

    /**
     * Exécute un appel {@code email_me}.
     *
     * @param userId    propriétaire du terminal (celui du tour)
     * @param workspace terminal du tour, déjà vérifié possédé
     * @param input     paramètres du modèle : seuls {@code subject} et {@code body} sont lus
     */
    public Outcome send(UUID userId, Workspace workspace, JsonNode input) {
        if (!isOpenFor(userId, workspace)) {
            return Outcome.refused("L'envoi de courriels n'existe que dans le terminal d'un poste, avec la Forge ou "
                    + "la Vigie : réponds sans lui.");
        }
        String subject = text(input, "subject").strip();
        String body = text(input, "body");
        if (subject.isEmpty()) {
            return Outcome.refused("Objet requis : donne « subject ».");
        }
        if (subject.length() > MAX_SUBJECT_CHARS || subject.chars().anyMatch(Character::isISOControl)) {
            return Outcome.refused("Objet invalide : une seule ligne, " + MAX_SUBJECT_CHARS + " caractères au plus.");
        }
        if (body.isBlank()) {
            return Outcome.refused("Corps requis : donne « body » en Markdown.");
        }
        if (body.length() > MAX_BODY_CHARS) {
            return Outcome.refused("Corps trop long (" + MAX_BODY_CHARS + " caractères au plus) : résume, ou dis à "
                    + "l'utilisateur où trouver le document.");
        }
        Optional<String> secret = ClientMailSecrets.find(subject).or(() -> ClientMailSecrets.find(body));
        if (secret.isPresent()) {
            return Outcome.refused("Courriel refusé : il contient manifestement " + secret.get() + ". Un secret ne "
                    + "voyage jamais par courriel ; retire-le et dis à l'utilisateur pourquoi.");
        }
        OffsetDateTime since = OffsetDateTime.now(clock).minus(LIMIT_WINDOW);
        if (emails.countByUserIdAndKindAndCreatedAtAfter(userId, ClientEmail.Kind.AGENT, since) >= DAILY_LIMIT) {
            return Outcome.refused("Limite de " + DAILY_LIMIT + " courriels par jour atteinte : aucun courriel n'a "
                    + "été envoyé. Dis-le à l'utilisateur.");
        }
        ResolvedRecipient recipient;
        try {
            recipient = addresses.resolveRecipient(userId, workspace.getHostId());
        } catch (RuntimeException e) {
            return Outcome.refused("Le poste de ce terminal est introuvable : aucun courriel n'a été envoyé.");
        }
        if (recipient.address() == null) {
            return Outcome.refused("Aucune adresse n'est connue pour ce compte : aucun courriel n'a été envoyé.");
        }
        ClientEmail queued = outbox.enqueue(new ClientMailOutbox.Draft(userId, workspace.getHostId(),
                workspace.getId(), ClientEmail.Kind.AGENT, recipient, subject,
                ClientMailRenderer.render(body, recipient.clientName())));
        String where = recipient.verifiedForClient()
                ? recipient.address() + " (adresse vérifiée de « " + recipient.clientName() + " »)"
                : recipient.address() + " — aucune adresse vérifiée pour « " + recipient.clientName()
                        + " », c'est l'adresse du compte : dis-le à l'utilisateur";
        return new Outcome("Courriel mis en file pour " + where + ", objet « " + subject + " ». L'envoi part en tâche "
                + "de fond ; son état de remise s'affiche dans le terminal.", false, ClientMailReceipt.of(queued));
    }

    private static String text(JsonNode input, String field) {
        return input == null || !input.hasNonNull(field) ? "" : input.path(field).asText("");
    }
}
