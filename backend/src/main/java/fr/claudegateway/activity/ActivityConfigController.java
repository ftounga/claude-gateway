package fr.claudegateway.activity;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.activity.dto.ActivitySettingsRequest;
import fr.claudegateway.activity.dto.ActivitySettingsResponse;
import fr.claudegateway.activity.dto.PosteRateRequest;
import fr.claudegateway.activity.dto.PosteRateResponse;
import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;
import jakarta.validation.Valid;

/**
 * Suivi d'activité et de revenu (F-124) — <b>configuration</b> (SF-124-01) : le TJM par poste et le
 * mois de départ du cumul.
 *
 * <p>Endpoints <b>JWT</b> (chaîne principale), gardés par le droit Forge
 * ({@link AtelierAccessService#requireRunnerAccess()}). L'identité vient du {@link CurrentUser},
 * jamais d'un paramètre ; le TJM d'un poste passe par {@code requireOwned} — un identifiant venu du
 * client ne suffit jamais à écrire dessous (404 pour un poste d'autrui).</p>
 */
@RestController
@RequestMapping("/activity")
public class ActivityConfigController {

    private final ActivityConfigService configService;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public ActivityConfigController(ActivityConfigService configService,
            AtelierAccessService atelierAccess, CurrentUser currentUser) {
        this.configService = configService;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    /** Le mois de départ du cumul, ou le défaut applicatif ({@code 2025-09}) si rien n'est réglé. */
    @GetMapping("/settings")
    public ActivitySettingsResponse settings() {
        atelierAccess.requireRunnerAccess();
        UUID userId = currentUser.requireId();
        return ActivitySettingsResponse.of(configService.startMonth(userId));
    }

    /** Fixe le mois de départ du cumul (format {@code YYYY-MM}). */
    @PutMapping("/settings")
    public ActivitySettingsResponse setSettings(@Valid @RequestBody ActivitySettingsRequest request) {
        atelierAccess.requireRunnerAccess();
        UUID userId = currentUser.requireId();
        return ActivitySettingsResponse.of(configService.setStartMonth(userId, request.startMonth()));
    }

    /** Les TJM des postes de l'utilisateur (un par poste ayant un TJM réglé). */
    @GetMapping("/rates")
    public List<PosteRateResponse> rates() {
        atelierAccess.requireRunnerAccess();
        UUID userId = currentUser.requireId();
        return configService.rates(userId).stream().map(PosteRateResponse::from).toList();
    }

    /** Fixe le TJM (centimes d'euro HT) d'un poste possédé. */
    @PutMapping("/rates/{hostId}")
    public PosteRateResponse setRate(@PathVariable UUID hostId,
            @Valid @RequestBody PosteRateRequest request) {
        atelierAccess.requireRunnerAccess();
        UUID userId = currentUser.requireId();
        return PosteRateResponse.from(
                configService.setRate(userId, hostId, request.dailyRateCents()));
    }

    /** Retire le TJM d'un poste possédé. Idempotent. */
    @DeleteMapping("/rates/{hostId}")
    public ResponseEntity<Void> clearRate(@PathVariable UUID hostId) {
        atelierAccess.requireRunnerAccess();
        UUID userId = currentUser.requireId();
        configService.clearRate(userId, hostId);
        return ResponseEntity.noContent().build();
    }
}
