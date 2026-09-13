package fr.claudegateway.pages;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Les liens de partage et le journal d'une page</b> (F-109 / SF-109-05), pour son propriétaire. La lecture
 * publique d'un lien vit dans {@link PagePublicController}.
 */
@RestController
@RequestMapping("/pages/{id}")
public class PageShareController {

    private final PageShareService shares;
    private final CurrentUser currentUser;

    public PageShareController(PageShareService shares, CurrentUser currentUser) {
        this.shares = shares;
        this.currentUser = currentUser;
    }

    /** Crée un lien : le jeton n'est rendu qu'ici, une fois. */
    @PostMapping("/shares")
    public ResponseEntity<CreatedShareResponse> create(@PathVariable UUID id,
            @RequestBody(required = false) CreateShareRequest body) {
        PageShareService.CreatedShare created = shares.create(currentUser.requireId(), id,
                body == null ? null : body.expiresInDays());
        PageShare share = created.share();
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreatedShareResponse(share.getId(),
                "/p/" + created.token(), share.getCreatedAt(), share.getExpiresAt()));
    }

    /** Les liens de la page, sans leur jeton. */
    @GetMapping("/shares")
    public List<ShareResponse> list(@PathVariable UUID id) {
        return shares.list(currentUser.requireId(), id).stream()
                .map(share -> new ShareResponse(share.getId(), share.getCreatedAt(), share.getExpiresAt(),
                        share.getRevokedAt(), share.getOpenCount(), share.getLastOpenedAt(), shares.stateOf(share)))
                .toList();
    }

    /** Révoque un lien : il cesse immédiatement. */
    @DeleteMapping("/shares/{shareId}")
    public ResponseEntity<Void> revoke(@PathVariable UUID id, @PathVariable UUID shareId) {
        shares.revoke(currentUser.requireId(), id, shareId);
        return ResponseEntity.noContent().build();
    }

    /** Le journal de la page, le plus récent d'abord. */
    @GetMapping("/journal")
    public List<JournalEntryResponse> journal(@PathVariable UUID id) {
        return shares.journal(currentUser.requireId(), id).stream()
                .map(event -> new JournalEntryResponse(event.getKind().name(), event.getOccurredAt(), event.getShareId(),
                        event.getVersion()))
                .toList();
    }

    /** Demande de lien : durée en jours (1 à 90, 7 par défaut). */
    public record CreateShareRequest(Integer expiresInDays) {
    }

    /** Lien créé, avec son adresse — la seule fois où le jeton est rendu. */
    public record CreatedShareResponse(UUID id, String url, OffsetDateTime createdAt, OffsetDateTime expiresAt) {
    }

    /** Un lien, sans son jeton. */
    public record ShareResponse(UUID id, OffsetDateTime createdAt, OffsetDateTime expiresAt, OffsetDateTime revokedAt,
            long openCount, OffsetDateTime lastOpenedAt, String state) {
    }

    /** Une ligne du journal. */
    public record JournalEntryResponse(String kind, OffsetDateTime occurredAt, UUID shareId, Integer version) {
    }
}
