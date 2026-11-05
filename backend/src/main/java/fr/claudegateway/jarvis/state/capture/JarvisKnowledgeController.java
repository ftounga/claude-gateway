package fr.claudegateway.jarvis.state.capture;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.jarvis.state.JarvisStateSpend;
import fr.claudegateway.runner.host.RunnerHostRepository;

/**
 * <b>« Ce que je sais », lot 2</b> (F-247 / SF-247-15) : les conversations capturées d'un client (effaçables une à une)
 * et la dépense du registre en euros (captures et constats, avec leurs plafonds propres). Tout sous le {@code user_id}
 * du principal ; un client ou une capture d'un autre compte ⇒ 404.
 */
@RestController
public class JarvisKnowledgeController {

    private final JarvisCaptureStore captures;
    private final JarvisStateSpend spend;
    private final RunnerHostRepository hosts;
    private final CurrentUser currentUser;

    public JarvisKnowledgeController(JarvisCaptureStore captures, JarvisStateSpend spend, RunnerHostRepository hosts,
            CurrentUser currentUser) {
        this.captures = captures;
        this.spend = spend;
        this.hosts = hosts;
        this.currentUser = currentUser;
    }

    /** Une capture lue et ses premières lignes, telles que l'écran les montre. */
    public record ConversationView(UUID id, String platform, String title, java.time.OffsetDateTime depositedAt,
            int lineCount, List<LineView> lines) {
    }

    /** Une ligne datée par la passerelle. */
    public record LineView(String speaker, boolean speakerKnown, java.time.OffsetDateTime saidAt, boolean approx,
            String text) {
    }

    /** Une part de la dépense, en euros. */
    public record SpendPart(BigDecimal monthEur, BigDecimal capEur) {
    }

    /** La dépense du registre ce mois-ci. */
    public record SpendView(SpendPart captures, SpendPart notes) {
    }

    @GetMapping("/jarvis/state/clients/{hostId}/conversations")
    public ResponseEntity<List<ConversationView>> conversations(@PathVariable UUID hostId) {
        UUID userId = currentUser.requireId();
        if (hosts.findByIdAndUserId(hostId, userId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        List<ConversationView> out = captures.recentCaptures(userId, hostId, JarvisCaptureStore.MAX_READ).stream()
                .filter(c -> "LUE".equals(c.status()))
                .map(c -> new ConversationView(c.id(), c.platform(), c.title(), c.depositedAt(), c.lineCount(),
                        captures.firstLines(userId, c.id(), 3).stream()
                                .map(l -> new LineView(l.speaker(), l.speakerKnown(), l.saidAt(), l.approx(),
                                        l.text().length() > 200 ? l.text().substring(0, 199) + "…" : l.text()))
                                .toList()))
                .toList();
        return ResponseEntity.ok(out);
    }

    @DeleteMapping("/jarvis/state/captures/{id}")
    public ResponseEntity<Void> deleteCapture(@PathVariable UUID id) {
        UUID userId = currentUser.requireId();
        return captures.delete(userId, id) ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @GetMapping("/jarvis/state/spend")
    public SpendView spend() {
        UUID userId = currentUser.requireId();
        return new SpendView(part(userId, JarvisStateSpend.Part.CAPTURE), part(userId, JarvisStateSpend.Part.NOTES));
    }

    private SpendPart part(UUID userId, JarvisStateSpend.Part p) {
        return new SpendPart(spend.monthEur(userId, p).setScale(2, java.math.RoundingMode.HALF_UP),
                spend.capEur(p).setScale(2, java.math.RoundingMode.HALF_UP));
    }
}
