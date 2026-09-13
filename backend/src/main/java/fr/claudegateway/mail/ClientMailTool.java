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
 * <p>Le schéma n'a <b>aucun champ destinataire</b>, et l'exécution ne lit que {@code subject}, {@code body} et
 * {@code attachments} (SF-110-03, {@link ClientMailAttachments}) : un {@code to} glissé par le modèle est ignoré. Le destinataire est résolu par la gateway depuis le <b>poste du
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
 * sur 24 heures glissantes ; pour les pièces jointes, un fichier de secrets, un secret dans une pièce texte et un
 * total au-delà de 10 Mo. Rien n'est mis en file quand il refuse.</p>
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
    private final ClientMailAttachments attachments;
    private final Clock clock;

    public ClientMailTool(SpaceEntitlementService entitlements, HostMailAddressService addresses,
            ClientMailOutbox outbox, ClientEmailRepository emails, ClientMailAttachments attachments, Clock clock) {
        this.entitlements = entitlements;
        this.addresses = addresses;
        this.outbox = outbox;
        this.emails = emails;
        this.attachments = attachments;
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
                        + "N'y mets JAMAIS de secret (mot de passe, jeton, clé) : l'outil refuse, pièces jointes "
                        + "comprises. " + TRANSCRIPT_RULE
                        + " Pièces jointes facultatives (attachments, " + ClientMailAttachments.MAX_ATTACHMENTS
                        + " au plus) : un fichier du poste (path, lu sur la machine), une page publiée de ce client "
                        + "(page_id : le fichier HTML et son lien privé ; link_only pour le lien seul), l'export "
                        + "Markdown du Radar de ce client (radar_export). 10 Mo au plus au total : au-delà, propose "
                        + "un lien plutôt qu'une pièce. Limite : " + DAILY_LIMIT + " courriels par jour.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "subject", Map.of("type", "string",
                                        "description", "Objet, une ligne, " + MAX_SUBJECT_CHARS + " caractères au plus."),
                                "body", Map.of("type", "string",
                                        "description", "Corps en Markdown."),
                                "attachments", Map.of("type", "array",
                                        "maxItems", ClientMailAttachments.MAX_ATTACHMENTS,
                                        "description", "Pièces jointes : chaque élément porte exactement une source.",
                                        "items", Map.of("type", "object",
                                                "properties", Map.of(
                                                        "path", Map.of("type", "string", "description",
                                                                "Fichier du poste : relatif au projet, absolu ou ~/…"),
                                                        "page_id", Map.of("type", "string", "description",
                                                                "Identifiant d'une page publiée de ce client."),
                                                        "radar_export", Map.of("type", "boolean", "description",
                                                                "Vrai pour joindre l'export Markdown du Radar de ce client."),
                                                        "link_only", Map.of("type", "boolean", "description",
                                                                "Pour une page : son lien privé seul, sans fichier."),
                                                        "name", Map.of("type", "string", "description",
                                                                "Nom du fichier joint (facultatif)."))))),
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
        return send(userId, workspace, UUID.randomUUID().toString(), input);
    }

    /**
     * Exécute un appel {@code email_me}, pièces jointes comprises (SF-110-03).
     *
     * @param userId    propriétaire du terminal (celui du tour)
     * @param workspace terminal du tour, déjà vérifié possédé
     * @param callId    identifiant de corrélation de l'appel : les lectures du poste en dérivent
     * @param input     paramètres du modèle : seuls {@code subject}, {@code body} et {@code attachments} sont lus
     */
    public Outcome send(UUID userId, Workspace workspace, String callId, JsonNode input) {
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
        ClientMailRenderer.Rendered rendered = ClientMailRenderer.render(body, recipient.clientName());
        ClientMailAttachments.Collected collected = attachments == null
                ? new ClientMailAttachments.Collected(List.of(), List.of(), null)
                : attachments.collect(userId, workspace, callId, input == null ? null : input.get("attachments"),
                        rendered.sizeBytes());
        if (collected.isRefused()) {
            return Outcome.refused(collected.refusal());
        }
        if (!collected.links().isEmpty()) {
            // Le lien privé d'une page part dans le corps, écrit par la gateway — jamais par le modèle.
            rendered = ClientMailRenderer.render(body + ClientMailAttachments.linksSection(collected.links()),
                    recipient.clientName());
        }
        if (rendered.sizeBytes() + collected.bytes() > ClientMailAttachments.MAX_TOTAL_BYTES) {
            return Outcome.refused("Courriel trop lourd : 10 Mo au plus au total (pièces et corps). Aucun courriel "
                    + "n'a été envoyé. Propose un lien à la place (link_only pour une page).");
        }
        ClientEmail queued;
        try {
            queued = outbox.enqueue(new ClientMailOutbox.Draft(userId, workspace.getHostId(),
                    workspace.getId(), ClientEmail.Kind.AGENT, recipient, subject, rendered,
                    collected.attachments()));
        } catch (RuntimeException e) {
            return Outcome.refused("Les pièces jointes n'ont pas pu être enregistrées : aucun courriel n'a été "
                    + "envoyé. Dis-le à l'utilisateur.");
        }
        String where = recipient.verifiedForClient()
                ? recipient.address() + " (adresse vérifiée de « " + recipient.clientName() + " »)"
                : recipient.address() + " — aucune adresse vérifiée pour « " + recipient.clientName()
                        + " », c'est l'adresse du compte : dis-le à l'utilisateur";
        return new Outcome("Courriel mis en file pour " + where + ", objet « " + subject + " »" + joined(collected)
                + ". L'envoi part en tâche de fond ; son état de remise s'affiche dans le terminal.", false,
                ClientMailReceipt.of(queued));
    }

    /** Ce que le modèle apprend des pièces et des liens : les noms, jamais les contenus. */
    private static String joined(ClientMailAttachments.Collected collected) {
        StringBuilder text = new StringBuilder();
        int count = collected.attachments().size();
        if (count > 0) {
            text.append(", ").append(count).append(count == 1 ? " pièce jointe (" : " pièces jointes (")
                    .append(String.join(", ", collected.attachments().stream()
                            .map(fr.claudegateway.email.ClientMailMessage.Attachment::name).toList()))
                    .append(')');
        }
        if (!collected.links().isEmpty()) {
            text.append(", lien privé de ").append(collected.links().size() == 1 ? "la page « " : "les pages « ")
                    .append(String.join(" », « ", collected.links().stream()
                            .map(ClientMailAttachments.PageLink::title).toList()))
                    .append(" » dans le corps");
        }
        return text.toString();
    }

    private static String text(JsonNode input, String field) {
        return input == null || !input.hasNonNull(field) ? "" : input.path(field).asText("");
    }
}
