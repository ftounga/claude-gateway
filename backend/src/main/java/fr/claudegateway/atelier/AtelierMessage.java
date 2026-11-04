package fr.claudegateway.atelier;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Message d'une conversation « Atelier » (F-28 / SF-28-02), attachée à un workspace. Isolation
 * multi-tenant : {@code user_id} porté sur chaque ligne ; tout accès filtre dessus.
 */
@Entity
@Table(name = "atelier_messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AtelierMessage {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** {@code USER} ou {@code ASSISTANT}. */
    @Column(name = "role", nullable = false, length = 16)
    private String role;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * Transcription du tour en mode Terminal (F-30 SF-30-09) : commandes, sorties, échec et coût,
     * sérialisés en JSON. {@code null} pour les tours du mode Assistant, qui n'en ont pas.
     *
     * <p>Donnée d'<b>affichage</b> : restituée en bloc à l'historique, jamais requêtée — d'où un
     * document plutôt qu'une table de blocs.</p>
     */
    @Column(name = "terminal_json", columnDefinition = "text")
    private String terminalJson;

    /**
     * Trajectoire d'outils du tour (F-39 / SF-39-03) : appels, arguments et résultats bornés,
     * sérialisés en JSON. {@code null} quand le tour n'a appelé aucun outil, et pour tous les
     * messages antérieurs à SF-39-03 — qui restent rejoués en texte seul.
     *
     * <p>Donnée de <b>rejeu</b>, jamais d'affichage : elle repart chez le fournisseur au tour
     * suivant pour que l'agent ne refasse pas ce qu'il vient de faire. Ce que l'utilisateur relit
     * reste porté par {@code terminal_json}.</p>
     */
    @Column(name = "tool_trace", columnDefinition = "text")
    private String toolTrace;

    /**
     * Le fil du message (F-221 / SF-221-01) : le numéro du chantier du sujet auquel il appartient, ou
     * {@code null} pour le fil commun — tous les messages d'avant F-221, et tout envoi sans chantier.
     * Le tour du chantier N relit le passé commun et son fil, jamais celui d'un autre chantier
     * ({@link ChantierThread}).
     */
    @Column(name = "chantier_number")
    private Integer chantierNumber;

    /** L'origine d'un message dit à la voix, dans l'écran Jarvis (F-231 / SF-231-06). */
    public static final String VIA_JARVIS = "JARVIS";

    /**
     * L'origine du message (F-231 / SF-231-06) : {@link #VIA_JARVIS} pour une demande dite à la voix et sa réponse,
     * {@code null} sinon (message tapé, et tous les messages d'avant F-231). Donnée d'<b>affichage</b> : la marque
     * « 🎙 via Jarvis » et l'encart « Pendant ton absence ».
     */
    @Column(name = "via", length = 16)
    private String via;

    /** Vrai si le message est né à la voix, ou répond à une demande née à la voix. */
    public boolean isViaJarvis() {
        return VIA_JARVIS.equals(via);
    }

    /** F-235 / SF-235-04 (D12) — un échange tenu par le cerveau rapide : écrit dans le fil, jamais rejoué. */
    public static final String BRAIN_FAST = "FAST";

    /** F-235 / SF-235-04 (D10) — la demande d'un tour Opus lancé par une délégation du cerveau rapide. */
    public static final String BRAIN_DELEGATED = "DELEGATED";

    /**
     * Le cerveau qui a tenu le message (F-235) : {@link #BRAIN_FAST} (réponse rapide, <b>exclue du rejeu</b> du tour
     * Opus — elle lui parvient par une note jointe au message suivant), {@link #BRAIN_DELEGATED} (demande d'un tour
     * Opus délégué), {@code null} sinon (tout le reste, et tout ce qui précède F-235).
     */
    @Column(name = "brain", length = 16)
    private String brain;

    /** Vrai si le message est une réponse rapide (ou la parole qui l'a appelée) : jamais rejoué comme un tour. */
    public boolean isFastBrain() {
        return BRAIN_FAST.equals(brain);
    }

    // ------------------------------------------------------------------ F-245 / SF-245-03 : la ligne-lien

    /** F-245 / SF-245-03 (D5) — le rôle d'une ligne-lien du fil Jarvis vers un sujet ou un chantier. */
    public static final String ROLE_LINK = "LINK";

    /** États d'une ligne-lien. */
    public static final String LINK_RUNNING = "EN_COURS";
    public static final String LINK_DONE = "FAIT";
    public static final String LINK_FAILED = "ECHEC";

    /** Le sujet visé par une ligne-lien (même {@code user_id}), {@code null} sinon. */
    @Column(name = "ref_workspace_id")
    private UUID refWorkspaceId;

    /** Le chantier visé par une ligne-lien, {@code null} pour le fil commun du sujet (ou hors ligne-lien). */
    @Column(name = "ref_chantier_number")
    private Integer refChantierNumber;

    /** La réponse d'Opus vers laquelle la ligne-lien ouvre, une fois le tour fini. */
    @Column(name = "ref_message_id")
    private UUID refMessageId;

    /** {@link #LINK_RUNNING}, {@link #LINK_DONE} ou {@link #LINK_FAILED} ; {@code null} hors ligne-lien. */
    @Column(name = "link_status", length = 16)
    private String linkStatus;

    /** Vrai si le message est une ligne-lien du fil Jarvis. */
    public boolean isLink() {
        return ROLE_LINK.equals(role);
    }
}
