package fr.claudegateway.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.billing.EntitlementSpace;
import fr.claudegateway.billing.SpaceEntitlementService;
import fr.claudegateway.email.ClientMailMessage;

/** L'outil {@code email_me} : garde, schéma, destinataire, refus, mise en file (F-110 / SF-110-02). */
@ExtendWith(MockitoExtension.class)
class ClientMailToolTest {

    private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");

    @Mock private SpaceEntitlementService entitlements;
    @Mock private HostMailAddressService addresses;
    @Mock private ClientMailOutbox outbox;
    @Mock private ClientEmailRepository emails;
    @Mock private ClientMailAttachments attachments;

    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final ResolvedRecipient verified = new ResolvedRecipient("franck@cagip.fr", true, "CAGIP");
    private ClientMailTool tool;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        tool = new ClientMailTool(entitlements, addresses, outbox, emails, attachments, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(attachments.collect(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new ClientMailAttachments.Collected(List.of(), List.of(), null));
        workspace = Workspace.builder().id(workspaceId).userId(userId).hostId(hostId).name("web")
                .executionTarget(WorkspaceExecutionTarget.RUNNER).build();
        lenient().when(entitlements.isEntitled(userId, EntitlementSpace.FORGE)).thenReturn(true);
        lenient().when(addresses.resolveRecipient(userId, hostId)).thenReturn(verified);
        lenient().when(outbox.enqueue(any())).thenAnswer(inv -> {
            ClientMailOutbox.Draft draft = inv.getArgument(0);
            return ClientEmail.builder().id(UUID.randomUUID()).recipient(draft.recipient().address())
                    .recipientVerified(draft.recipient().verifiedForClient()).clientName(draft.recipient().clientName())
                    .subject(draft.subject()).status(ClientEmailStatus.PENDING).build();
        });
    }

    private ClientMailTool.Outcome send(String json) throws Exception {
        return tool.send(userId, workspace, mapper.readTree(json));
    }

    @Test
    void theGuardWantsAHostAndForgeOrVigieAndTheAdministratorInheritsIt() {
        assertThat(tool.isOpenFor(userId, workspace)).isTrue();

        when(entitlements.isEntitled(userId, EntitlementSpace.FORGE)).thenReturn(false);
        assertThat(tool.isOpenFor(userId, workspace)).isFalse();
        // La Vigie seule suffit ; l'administrateur passe par SpaceEntitlementService, qui le dit titulaire.
        when(entitlements.isEntitled(userId, EntitlementSpace.VIGIE)).thenReturn(true);
        assertThat(tool.isOpenFor(userId, workspace)).isTrue();

        Workspace noHost = Workspace.builder().id(workspaceId).userId(userId).name("web")
                .executionTarget(WorkspaceExecutionTarget.RUNNER).build();
        assertThat(tool.isOpenFor(userId, noHost)).isFalse();
        assertThat(tool.toolFor(userId, noHost)).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSchemaHasNoRecipientAndTheDescriptionNamesTheResolvedOne() {
        AgentTool definition = tool.toolFor(userId, workspace).orElseThrow();

        Map<String, Object> properties = (Map<String, Object>) definition.inputSchema().get("properties");
        assertThat(properties).containsOnlyKeys("subject", "body", "attachments");
        assertThat(definition.inputSchema()).containsEntry("additionalProperties", false);
        assertThat(definition.description()).contains("franck@cagip.fr", "adresse vérifiée du client « CAGIP »",
                "jamais à un tiers", "downloadBlocked", "50 courriels");
    }

    @Test
    void theFallbackIsSaidInTheDescriptionAndTheResult() throws Exception {
        when(addresses.resolveRecipient(userId, hostId))
                .thenReturn(new ResolvedRecipient("ntounga@gmail.com", false, "CAGIP"));

        assertThat(tool.toolFor(userId, workspace).orElseThrow().description())
                .contains("Aucune adresse vérifiée pour « CAGIP »", "ntounga@gmail.com", "AVANT d'envoyer");
        ClientMailTool.Outcome outcome = send("{\"subject\":\"CR\",\"body\":\"texte\"}");
        assertThat(outcome.error()).isFalse();
        assertThat(outcome.content()).contains("ntounga@gmail.com", "adresse du compte", "dis-le à l'utilisateur");
        assertThat(outcome.receipt().recipientVerified()).isFalse();
    }

    @Test
    void aRecipientGivenByTheModelIsIgnored() throws Exception {
        ClientMailTool.Outcome outcome = send("{\"subject\":\"Compte rendu MFA\",\"body\":\"# CR\\n- ok\","
                + "\"to\":\"tiers@ailleurs.fr\",\"cc\":\"autre@ailleurs.fr\"}");

        ArgumentCaptor<ClientMailOutbox.Draft> draft = ArgumentCaptor.forClass(ClientMailOutbox.Draft.class);
        verify(outbox).enqueue(draft.capture());
        assertThat(draft.getValue().recipient()).isEqualTo(verified);
        assertThat(draft.getValue().userId()).isEqualTo(userId);
        assertThat(draft.getValue().hostId()).isEqualTo(hostId);
        assertThat(draft.getValue().workspaceId()).isEqualTo(workspaceId);
        assertThat(draft.getValue().kind()).isEqualTo(ClientEmail.Kind.AGENT);
        assertThat(draft.getValue().rendered().html()).contains("<h1>CR</h1>").doesNotContain("tiers");
        assertThat(outcome.receipt().recipient()).isEqualTo("franck@cagip.fr");
        assertThat(outcome.receipt().subject()).isEqualTo("Compte rendu MFA");
        assertThat(outcome.content()).contains("franck@cagip.fr").doesNotContain("tiers");
    }

    @Test
    void invalidSubjectOrBodyIsRefusedWithoutQueueing() throws Exception {
        for (String json : List.of("{\"body\":\"x\"}", "{\"subject\":\"  \",\"body\":\"x\"}",
                "{\"subject\":\"ligne 1\\nBcc: x@y.fr\",\"body\":\"x\"}",
                "{\"subject\":\"" + "o".repeat(201) + "\",\"body\":\"x\"}", "{\"subject\":\"CR\"}",
                "{\"subject\":\"CR\",\"body\":\"" + "b".repeat(100_001) + "\"}")) {
            assertThat(send(json).error()).as(json.length() > 80 ? json.substring(0, 80) : json).isTrue();
        }
        verify(outbox, never()).enqueue(any());
    }

    @Test
    void aManifestSecretIsRefusedAndNamedWithoutBeingRepeated() throws Exception {
        ClientMailTool.Outcome outcome = send("{\"subject\":\"Accès\",\"body\":\"mot de passe : Hunter2024!\"}");

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("un mot de passe").doesNotContain("Hunter2024");
        verify(outbox, never()).enqueue(any());
    }

    @Test
    void theFiftyFirstMailOfTheDayIsRefused() throws Exception {
        when(emails.countByUserIdAndKindAndCreatedAtAfter(eq(userId), eq(ClientEmail.Kind.AGENT),
                eq(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusHours(24)))).thenReturn(50L);

        ClientMailTool.Outcome outcome = send("{\"subject\":\"CR\",\"body\":\"texte\"}");

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("Limite de 50 courriels par jour");
        verify(outbox, never()).enqueue(any());
    }

    // ---------------------------------------------------------------- pièces jointes (SF-110-03)

    @Test
    @SuppressWarnings("unchecked")
    void theAttachmentsSchemaNamesItsThreeSourcesAndStillNoRecipient() {
        AgentTool definition = tool.toolFor(userId, workspace).orElseThrow();

        Map<String, Object> properties = (Map<String, Object>) definition.inputSchema().get("properties");
        Map<String, Object> items = (Map<String, Object>) ((Map<String, Object>) properties.get("attachments")).get("items");
        assertThat((Map<String, Object>) items.get("properties"))
                .containsOnlyKeys("path", "page_id", "radar_export", "link_only", "name");
        assertThat(definition.description()).contains("10 Mo", "propose un lien", "pièces jointes comprises");
    }

    @Test
    void collectedAttachmentsGoToTheQueueAndPageLinksToTheBody() throws Exception {
        UUID pageId = UUID.randomUUID();
        ClientMailMessage.Attachment pdf = new ClientMailMessage.Attachment("cr.pdf", "application/pdf", new byte[] {1, 2});
        when(attachments.collect(eq(userId), eq(workspace), eq("call-1"), any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new ClientMailAttachments.Collected(List.of(pdf),
                        List.of(new ClientMailAttachments.PageLink("Sujet MFA", "https://app/pages/" + pageId)), null));
        org.mockito.Mockito.doAnswer(inv -> {
            ClientMailOutbox.Draft draft = inv.getArgument(0);
            return ClientEmail.builder().id(UUID.randomUUID()).recipient("franck@cagip.fr").recipientVerified(true)
                    .clientName("CAGIP").subject(draft.subject()).attachmentCount(draft.attachments().size())
                    .status(ClientEmailStatus.PENDING).build();
        }).when(outbox).enqueue(any());

        ClientMailTool.Outcome outcome = tool.send(userId, workspace, "call-1", mapper.readTree(
                "{\"subject\":\"CR\",\"body\":\"# CR\",\"attachments\":[{\"path\":\"cr.pdf\"},{\"page_id\":\"" + pageId + "\"}]}"));

        assertThat(outcome.error()).isFalse();
        ArgumentCaptor<ClientMailOutbox.Draft> draft = ArgumentCaptor.forClass(ClientMailOutbox.Draft.class);
        verify(outbox).enqueue(draft.capture());
        assertThat(draft.getValue().attachments()).containsExactly(pdf);
        assertThat(draft.getValue().rendered().html()).contains("Sujet MFA", "https://app/pages/" + pageId);
        assertThat(draft.getValue().rendered().text()).contains("https://app/pages/" + pageId);
        assertThat(outcome.receipt().attachmentCount()).isEqualTo(1);
        assertThat(outcome.content()).contains("1 pièce jointe (cr.pdf)", "lien privé de la page « Sujet MFA »");
    }

    @Test
    void aRefusedAttachmentQueuesNothing() throws Exception {
        when(attachments.collect(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new ClientMailAttachments.Collected(List.of(), List.of(), "Courriel trop lourd : propose un lien."));

        ClientMailTool.Outcome outcome = send("{\"subject\":\"CR\",\"body\":\"x\",\"attachments\":[{\"path\":\"gros.zip\"}]}");

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("propose un lien");
        verify(outbox, never()).enqueue(any());
    }

    @Test
    void aStorageFailureIsSaidAndNothingIsClaimedSent() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("s3")).when(outbox).enqueue(any());

        ClientMailTool.Outcome outcome = send("{\"subject\":\"CR\",\"body\":\"x\"}");

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("aucun courriel n'a été envoyé");
        assertThat(outcome.receipt()).isNull();
    }

    @Test
    void theSecretCheckOnTheBodyStillRunsBeforeAnyAttachmentIsRead() throws Exception {
        send("{\"subject\":\"CR\",\"body\":\"password=Hunter2024!\",\"attachments\":[{\"path\":\"cr.pdf\"}]}");

        verify(attachments, never()).collect(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void outsideTheGuardACallIsRefused() throws Exception {
        when(entitlements.isEntitled(userId, EntitlementSpace.FORGE)).thenReturn(false);
        when(entitlements.isEntitled(userId, EntitlementSpace.VIGIE)).thenReturn(false);

        assertThat(send("{\"subject\":\"CR\",\"body\":\"texte\"}").error()).isTrue();
        verify(outbox, never()).enqueue(any());
    }
}
