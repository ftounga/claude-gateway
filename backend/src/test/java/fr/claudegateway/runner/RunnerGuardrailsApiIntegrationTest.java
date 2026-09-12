package fr.claudegateway.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.runner.audit.RunnerAudit;
import fr.claudegateway.runner.audit.RunnerAuditRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Garde-fous d'exécution et traçabilité, vus de l'API (F-38 / SF-38-08) : réponse à une demande
 * d'autorisation, journal d'activité, coupe-circuit, et interdiction de désactiver la validation en
 * cible {@code RUNNER} (décision D7). L'isolation {@code user_id} est vérifiée sur chacun.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunnerGuardrailsApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private fr.claudegateway.runner.host.RunnerHostRepository hostRepository;
    @Autowired private RunnerTokenRepository runnerTokenRepository;
    @Autowired private RunnerAuditRepository runnerAuditRepository;
    @Autowired private RunnerTokenService tokenService;
    @Autowired private JwtService jwtService;

    private User owner;
    private String ownerToken;
    private Workspace workspace;
    /** Poste du projet (F-48 / SF-48-01) : le coupe-circuit coupe la MACHINE, pas un dossier. */
    private fr.claudegateway.runner.host.RunnerHost host;
    private String otherToken;

    @BeforeEach
    void setUp() {
        runnerAuditRepository.deleteAll();
        runnerTokenRepository.deleteAll();
        workspaceRepository.deleteAll();
        hostRepository.deleteAll();
        userRepository.deleteAll();

        owner = seedUser("owner-guard@example.com");
        ownerToken = jwtService.generateToken(owner);
        host = hostRepository.save(
                fr.claudegateway.runner.host.RunnerHost.builder().userId(owner.getId()).name("Poste")
                        .build());
        workspace = workspaceRepository.save(Workspace.builder()
                .userId(owner.getId()).name("Projet").executionTarget(WorkspaceExecutionTarget.RUNNER)
                .hostId(host.getId()).projectPath("projet")
                .build());
        otherToken = jwtService.generateToken(seedUser("other-guard@example.com"));
    }

    private User seedUser(String email) {
        return userRepository.save(User.builder()
                .email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
    }

    private String url(String suffix) {
        return "/api/workspaces/" + workspace.getId() + suffix;
    }

    /** Le coupe-circuit et les jetons vivent désormais sur le poste (F-48 / SF-48-01). */
    private String hostUrl(String suffix) {
        return "/api/runner-hosts/" + host.getId() + suffix;
    }

    private void seedAudit(String tool, String target, String outcome) {
        runnerAuditRepository.save(RunnerAudit.builder()
                .userId(owner.getId()).hostId(host.getId()).workspaceId(workspace.getId())
                .callId(UUID.randomUUID().toString().substring(0, 20))
                .tool(tool).target(target).outcome(outcome).durationMs(5L)
                .createdAt(OffsetDateTime.now())
                .build());
    }

    // ---------- Réponse à une demande d'autorisation ----------

    @Test
    void answeringWhenNothingIsPendingIsAConflictNotASilentSuccess() throws Exception {
        mockMvc.perform(post(url("/chat/confirm")).contextPath("/api")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolUseId\":\"toolu_inconnu\",\"decision\":\"allow\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("no_pending_confirmation"));
    }

    @Test
    void answeringWithoutADecisionIsRejected() throws Exception {
        mockMvc.perform(post(url("/chat/confirm")).contextPath("/api")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolUseId\":\"toolu_1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void answeringOnAnotherUsersProjectIsNotFound() throws Exception {
        mockMvc.perform(post(url("/chat/confirm")).contextPath("/api")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolUseId\":\"toolu_1\",\"decision\":\"allow\"}"))
                .andExpect(status().isNotFound());
    }

    // ---------- Journal d'activité ----------

    @Test
    void theOwnerReadsTheJournalNewestFirst() throws Exception {
        seedAudit("read_file", "src/a.ts", "OK");
        seedAudit("bash", "npm test", "DENIED");

        mockMvc.perform(get(url("/runner/audit")).contextPath("/api")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].tool").value("bash"))
                .andExpect(jsonPath("$[0].outcome").value("DENIED"))
                .andExpect(jsonPath("$[0].target").value("npm test"));
    }

    @Test
    void anAbsurdLimitIsClampedRatherThanRefused() throws Exception {
        seedAudit("read_file", "src/a.ts", "OK");
        seedAudit("read_file", "src/b.ts", "OK");

        mockMvc.perform(get(url("/runner/audit")).contextPath("/api").param("limit", "1")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get(url("/runner/audit")).contextPath("/api").param("limit", "100000")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void theJournalOfAnotherUsersProjectIsNotFound() throws Exception {
        seedAudit("bash", "npm test", "OK");

        mockMvc.perform(get(url("/runner/audit")).contextPath("/api")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    // ---------- Coupe-circuit ----------

    @Test
    void theKillSwitchRevokesEveryTokenAndFallsBackToSandbox() throws Exception {
        UUID tokenId = tokenService.issue(owner.getId(), host.getId(), "poste").token().getId();

        mockMvc.perform(post(hostUrl("/kill")).contextPath("/api")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revokedTokens").value(1))
                // Tous les projets du poste reviennent au bac à sable, pas seulement un.
                .andExpect(jsonPath("$.workspacesReturned").value(1));

        assertThat(runnerTokenRepository.findById(tokenId).orElseThrow().getRevokedAt()).isNotNull();
        assertThat(workspaceRepository.findById(workspace.getId()).orElseThrow()
                .executionTargetOrDefault()).isEqualTo(WorkspaceExecutionTarget.SANDBOX);
    }

    @Test
    void cuttingTwiceIsNotAnError() throws Exception {
        mockMvc.perform(post(hostUrl("/kill")).contextPath("/api")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
        mockMvc.perform(post(hostUrl("/kill")).contextPath("/api")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revokedTokens").value(0))
                .andExpect(jsonPath("$.disconnected").value(false));
    }

    @Test
    void theKillSwitchOfAnotherUsersHostIsNotFound() throws Exception {
        mockMvc.perform(post(hostUrl("/kill")).contextPath("/api")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    // ---------- D7, amendée par SF-38-20 : la validation redevient désactivable ----------

    @Test
    void theConfirmationCanNowBeSwitchedOffOnARunnerProject() throws Exception {
        // SF-38-08 (D7) l'interdisait. Le banc d'essai a montré le prix de cette rigidité : une
        // procédure de treize étapes demandait des dizaines de clics, et une garde qu'on subit
        // finit par être contournée plutôt que respectée. C'est une décision de l'utilisateur sur
        // SA machine — et ce qui disparaît est le clic, jamais la trace : le journal d'audit
        // continue de tout consigner, le coupe-circuit reste immédiat.
        mockMvc.perform(put(url("/agent/confirmation")).contextPath("/api")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk());
    }

    @Test
    void switchingToTheRunnerTargetLeavesTheConfirmationOff() throws Exception {
        // Le réglage est posé à false EXPLICITEMENT : depuis F-73 / SF-73-02 le défaut est `true`,
        // et ce test-ci porte sur la bascule de cible, qui ne doit toucher à rien.
        Workspace sandbox = workspaceRepository.save(Workspace.builder()
                .userId(owner.getId()).name("Autre")
                .executionTarget(WorkspaceExecutionTarget.SANDBOX)
                .agentAskBeforeBash(false).build());

        mockMvc.perform(put("/api/workspaces/" + sandbox.getId() + "/execution-target")
                        .contextPath("/api")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"executionTarget\":\"RUNNER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionTarget").value("RUNNER"))
                // La bascule armait la porte (SF-38-08, D7). SF-47-04 a retiré ce forçage et F-73
                // le laisse retiré : on n'arme pas dans le dos d'un utilisateur qui a éteint. Ce
                // que F-73 change est la valeur de DÉPART d'un projet neuf, jamais une bascule.
                .andExpect(jsonPath("$.askBeforeBash").value(false));
    }

    @Test
    void aFreshLocalProjectAsksBeforeRunningAnything() throws Exception {
        // SF-73-04 : la porte est DÉSARMÉE à la création, à titre TEMPORAIRE (décision du PO du
        // 2026-09-12). SF-73-02 l'avait armée quelques heures plus tôt pour remplacer le confinement
        // retiré par SF-73-01 — mais le dispositif suppose que l'invite s'affiche, et elle ne
        // s'affiche pas : deux tours en production ont expiré au bout de 120 s sans décision.
        mockMvc.perform(post("/api/workspaces/local")
                        .contextPath("/api")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Projet neuf\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionTarget").value("RUNNER"))
                // SF-73-04 : DÉSARMÉE par défaut, à titre temporaire (décision du PO du
                // 2026-09-12) — l'invite d'autorisation ne s'affiche pas, et armée la
                // porte rendait toute première commande d'un projet neuf impossible.
                .andExpect(jsonPath("$.askBeforeBash").value(false));
    }

    @Test
    void anExistingProjectKeepsTheSettingItCarries() throws Exception {
        // Aucune donnée n'est réécrite (F-73, D4) : un projet qui avait éteint la porte la garde
        // éteinte, et la migration 073 ne touche que le DÉFAUT de colonne.
        Workspace existing = workspaceRepository.save(Workspace.builder()
                .userId(owner.getId()).name("Existant")
                .executionTarget(WorkspaceExecutionTarget.RUNNER)
                .agentAskBeforeBash(false).build());

        mockMvc.perform(get("/api/workspaces/" + existing.getId())
                        .contextPath("/api")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.askBeforeBash").value(false));
    }

    @Test
    void switchingToTheRunnerTargetLeavesAnArmedConfirmationArmed() throws Exception {
        Workspace armed = workspaceRepository.save(Workspace.builder()
                .userId(owner.getId()).name("Verrouille")
                .executionTarget(WorkspaceExecutionTarget.SANDBOX)
                .agentAskBeforeBash(true).build());

        mockMvc.perform(put("/api/workspaces/" + armed.getId() + "/execution-target")
                        .contextPath("/api")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"executionTarget\":\"RUNNER\"}"))
                .andExpect(status().isOk())
                // On ne désarme pas plus qu'on n'arme dans le dos de l'utilisateur : le réglage
                // qu'il a posé est le sien, et la bascule de cible ne le relit pas.
                .andExpect(jsonPath("$.askBeforeBash").value(true));
    }
}
