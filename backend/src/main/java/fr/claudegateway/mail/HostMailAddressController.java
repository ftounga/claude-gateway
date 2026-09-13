package fr.claudegateway.mail;

import java.util.UUID;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;

/**
 * <b>L'adresse de réception d'un client</b> (F-110 / SF-110-01), réglée depuis l'en-tête du client dans la
 * Forge et la Vigie.
 *
 * <p>JWT ; garde du <b>runner</b> (Forge ou Vigie, administrateur d'office) ; l'identité vient du
 * {@link CurrentUser}, le poste est vérifié possédé par le service (404 sinon). Aucune logique ici.</p>
 */
@RestController
@RequestMapping("/runner-hosts/{hostId}/mail-address")
public class HostMailAddressController {

    private final HostMailAddressService service;
    private final AtelierAccessService access;
    private final CurrentUser currentUser;

    public HostMailAddressController(HostMailAddressService service, AtelierAccessService access,
            CurrentUser currentUser) {
        this.service = service;
        this.access = access;
        this.currentUser = currentUser;
    }

    @GetMapping
    public HostMailAddressView read(@PathVariable UUID hostId) {
        access.requireRunnerAccess();
        return service.view(currentUser.requireId(), hostId);
    }

    @PutMapping
    public HostMailAddressView declare(@PathVariable UUID hostId, @RequestBody(required = false) AddressRequest request) {
        access.requireRunnerAccess();
        return service.declare(currentUser.requireId(), hostId, request == null ? null : request.address());
    }

    @PostMapping("/verify")
    public HostMailAddressView verify(@PathVariable UUID hostId, @RequestBody(required = false) CodeRequest request) {
        access.requireRunnerAccess();
        return service.verify(currentUser.requireId(), hostId, request == null ? null : request.code());
    }

    @PostMapping("/code")
    public HostMailAddressView resend(@PathVariable UUID hostId) {
        access.requireRunnerAccess();
        return service.resend(currentUser.requireId(), hostId);
    }

    @DeleteMapping
    public HostMailAddressView remove(@PathVariable UUID hostId) {
        access.requireRunnerAccess();
        return service.remove(currentUser.requireId(), hostId);
    }

    /** Corps de la déclaration. */
    public record AddressRequest(String address) {
    }

    /** Corps de la vérification. */
    public record CodeRequest(String code) {
    }
}
