package fr.claudegateway.jarvis.state;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.runner.host.RunnerHostService;

/**
 * <b>Le registre d'état, côté écran</b> (F-247 / SF-247-15) : le volet « Ce que je sais » des réglages de {@code /jarvis}.
 * Par client du compte : le réglage (activé d'office, couper purge), les familles, les sources, les rétentions,
 * « effacer tout », et les faits connus (datés, sources), effaçables un à un. Tout sous le {@code user_id} du principal ;
 * un client d'un autre compte ⇒ 404.
 */
@RestController
public class JarvisStateController {

    private final JarvisStateRegistry registry;
    private final RunnerHostService hosts;
    private final RunnerHostRepository hostRepository;
    private final JarvisStateFactRepository facts;
    private final CurrentUser currentUser;

    public JarvisStateController(JarvisStateRegistry registry, RunnerHostService hosts,
            RunnerHostRepository hostRepository, JarvisStateFactRepository facts, CurrentUser currentUser) {
        this.registry = registry;
        this.hosts = hosts;
        this.hostRepository = hostRepository;
        this.facts = facts;
        this.currentUser = currentUser;
    }

    /** Le réglage d'un client, tel que l'écran le montre et le renvoie. */
    public record ClientSettings(UUID hostId, String name, boolean enabled, List<String> disabledFamilies,
            boolean extractFromTurns, boolean notesEnabled, boolean releveEnabled, boolean capturesEnabled,
            int factRetentionDays, int captureRetentionDays, long facts) {
    }

    /** Ce que l'écran envoie pour un client. */
    public record SettingsRequest(Boolean enabled, List<String> disabledFamilies, Boolean extractFromTurns,
            Boolean notesEnabled, Boolean releveEnabled, Boolean capturesEnabled, Integer factRetentionDays,
            Integer captureRetentionDays) {
    }

    /** Un fait connu, tel que l'écran le montre. */
    public record FactView(UUID id, String objectKind, String objectKey, String attribute, String value,
            String previousValue, OffsetDateTime observedAt, String source, String confidence) {
    }

    @GetMapping("/jarvis/state/clients")
    public List<ClientSettings> clients() {
        UUID userId = currentUser.requireId();
        List<ClientSettings> out = new ArrayList<>();
        for (RunnerHost h : hosts.list(userId)) {
            if (userId.equals(h.getUserId())) {
                out.add(view(h, registry.settingsOf(userId, h.getId())));
            }
        }
        return out;
    }

    @PutMapping("/jarvis/state/clients/{hostId}")
    public ResponseEntity<ClientSettings> save(@PathVariable UUID hostId, @RequestBody SettingsRequest body) {
        UUID userId = currentUser.requireId();
        RunnerHost host = hostRepository.findByIdAndUserId(hostId, userId).orElse(null);
        if (host == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (body == null || !valid(body)) {
            return ResponseEntity.badRequest().build();
        }
        JarvisStateSettings s = registry.settingsOf(userId, hostId);
        if (body.enabled() != null) {
            s.setEnabled(body.enabled());
        }
        if (body.disabledFamilies() != null) {
            s.setDisabledFamilies(body.disabledFamilies().isEmpty() ? null : String.join(",",
                    body.disabledFamilies().stream().map(f -> f.strip().toUpperCase(Locale.ROOT)).distinct().toList()));
        }
        if (body.extractFromTurns() != null) {
            s.setExtractFromTurns(body.extractFromTurns());
        }
        if (body.notesEnabled() != null) {
            s.setNotesEnabled(body.notesEnabled());
        }
        if (body.releveEnabled() != null) {
            s.setReleveEnabled(body.releveEnabled());
        }
        if (body.capturesEnabled() != null) {
            s.setCapturesEnabled(body.capturesEnabled());
        }
        if (body.factRetentionDays() != null) {
            s.setFactRetentionDays(body.factRetentionDays());
        }
        if (body.captureRetentionDays() != null) {
            s.setCaptureRetentionDays(body.captureRetentionDays());
        }
        return ResponseEntity.ok(view(host, registry.save(s)));
    }

    /** Les faits connus d'un client (20 au plus, les plus récents, ou d'une famille). */
    @GetMapping("/jarvis/state/clients/{hostId}/facts")
    public ResponseEntity<List<FactView>> facts(@PathVariable UUID hostId,
            @RequestParam(required = false) String famille) {
        UUID userId = currentUser.requireId();
        if (hostRepository.findByIdAndUserId(hostId, userId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        List<JarvisStateRegistry.Fact> read = famille == null || famille.isBlank()
                ? registry.recent(userId, hostId, JarvisStateRegistry.MAX_READ)
                : registry.byKind(userId, hostId, famille, JarvisStateRegistry.MAX_READ);
        return ResponseEntity.ok(read.stream().map(f -> new FactView(f.id(), f.objectKind(), f.objectKey(),
                f.attribute(), f.value(), f.previousValue(), f.observedAt(), f.source(), f.confidence())).toList());
    }

    /** « Effacer tout » pour un client. */
    @DeleteMapping("/jarvis/state/clients/{hostId}/facts")
    public ResponseEntity<Void> eraseAll(@PathVariable UUID hostId) {
        UUID userId = currentUser.requireId();
        if (hostRepository.findByIdAndUserId(hostId, userId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        registry.eraseAll(userId, hostId);
        return ResponseEntity.noContent().build();
    }

    /** Effacer un fait. */
    @DeleteMapping("/jarvis/state/facts/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        return registry.delete(currentUser.requireId(), id) ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    static boolean valid(SettingsRequest b) {
        if (b.factRetentionDays() != null && (b.factRetentionDays() < 1 || b.factRetentionDays() > 365)) {
            return false;
        }
        if (b.captureRetentionDays() != null && (b.captureRetentionDays() < 1 || b.captureRetentionDays() > 365)) {
            return false;
        }
        return b.disabledFamilies() == null || b.disabledFamilies().stream()
                .allMatch(f -> f != null && JarvisStateVocabulary.KINDS.contains(f.strip().toUpperCase(Locale.ROOT)));
    }

    private ClientSettings view(RunnerHost h, JarvisStateSettings s) {
        return new ClientSettings(h.getId(), h.getName(), s.isEnabled(), List.copyOf(s.disabledFamilySet()),
                s.isExtractFromTurns(), s.isNotesEnabled(), s.isReleveEnabled(), s.isCapturesEnabled(),
                s.getFactRetentionDays(), s.getCaptureRetentionDays(),
                facts.countByUserIdAndHostId(s.getUserId(), h.getId()));
    }
}
