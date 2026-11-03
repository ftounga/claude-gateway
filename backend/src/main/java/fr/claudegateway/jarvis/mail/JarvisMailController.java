package fr.claudegateway.jarvis.mail;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Les boîtes mail de Jarvis</b> (F-242 / SF-242-01) — {@code /api/jarvis/mail/accounts}, authentifié, sur le seul
 * {@code user_id} du principal. <b>Aucun point d'accès administrateur</b> aux boîtes d'autrui. Le secret n'est jamais
 * renvoyé.
 */
@RestController
public class JarvisMailController {

    /** {@code {"address": "…", "label": "Perso", "password": "abcd efgh ijkl mnop"}}. */
    public record LinkRequest(String address, String label, String password) {
    }

    private final JarvisMailAccountService accounts;
    private final JarvisMailItemService items;
    private final CurrentUser currentUser;

    public JarvisMailController(JarvisMailAccountService accounts, JarvisMailItemService items,
            CurrentUser currentUser) {
        this.accounts = accounts;
        this.items = items;
        this.currentUser = currentUser;
    }

    /** SF-242-02 — le cache du courrier, les plus récents d'abord ({@code triage} facultatif, 100 au plus). */
    @GetMapping("/jarvis/mail/items")
    public List<JarvisMailItemService.ItemView> items(@RequestParam(required = false) String triage,
            @RequestParam(required = false) Integer limit) {
        return items.list(currentUser.requireId(), triage, limit);
    }

    @GetMapping("/jarvis/mail/accounts")
    public List<JarvisMailAccountService.View> list() {
        return accounts.list(currentUser.requireId());
    }

    @PostMapping("/jarvis/mail/accounts")
    public ResponseEntity<JarvisMailAccountService.View> link(@RequestBody(required = false) LinkRequest body) {
        if (body == null) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(accounts.link(currentUser.requireId(), body.address(), body.label(), body.password()));
    }

    @PostMapping("/jarvis/mail/accounts/{id}/test")
    public JarvisMailAccountService.View test(@PathVariable UUID id) {
        return accounts.test(currentUser.requireId(), id);
    }

    @DeleteMapping("/jarvis/mail/accounts/{id}")
    public ResponseEntity<Void> revoke(@PathVariable UUID id) {
        accounts.revoke(currentUser.requireId(), id);
        return ResponseEntity.noContent().build();
    }
}
