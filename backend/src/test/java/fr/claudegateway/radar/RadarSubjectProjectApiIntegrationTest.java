package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.runner.host.ClientSpace;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRole;

/**
 * <b>Le lien entre un sujet et un projet</b> (F-106 / SF-106-06), de bout en bout par l'API : lier, délier
 * (= refuser), confirmer une proposition, la passerelle de la Forge, la purge — et l'isolation par poste.
 */
class RadarSubjectProjectApiIntegrationTest extends RadarIntegrationTestBase {

    @Autowired private RadarSubjectProjectRepository subjectProjects;
    @Autowired private RadarSubjectProjectService service;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private RadarPurgeService purgeService;

    @Override
    protected void cleanRadarTables() {
        subjectProjects.deleteAll();
        workspaceRepository.deleteAll();
        super.cleanRadarTables();
    }

    private Workspace project(RadarScope scope, String name, String path) {
        return workspaceRepository.save(Workspace.builder().userId(scope.userId()).hostId(scope.hostId())
                .name(name).projectPath(path).executionTarget(WorkspaceExecutionTarget.RUNNER).build());
    }

    private RadarSubject subject(RadarScope scope, String name) {
        return registry.createSubject(scope, name, RadarSubjectState.ADVANCING, ids(proof(scope, "On en parle.")));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
        return request.contextPath("/api").header("Authorization", "Bearer " + token);
    }

    private String linkUrl(RadarScope scope, RadarSubject subject, Workspace project) {
        return url(scope, "/subjects/" + subject.getId() + "/projects/" + project.getId());
    }

    @Test
    @DisplayName("lier puis délier : le lien apparaît, puis la paire est retenue comme refusée et redevient candidate")
    void linkThenUnlink() throws Exception {
        RadarSubject mfa = subject(aliceA, "Pilote MFA");
        Workspace billing = project(aliceA, "billing", "clients/billing-api");
        project(aliceA, "infra", "infra");

        mockMvc.perform(as(get(url(aliceA, "/subjects/" + mfa.getId() + "/projects")), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inForge").value(true))
                .andExpect(jsonPath("$.links.length()").value(0))
                .andExpect(jsonPath("$.candidates.length()").value(2));

        mockMvc.perform(as(put(linkUrl(aliceA, mfa, billing)), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links.length()").value(1))
                .andExpect(jsonPath("$.links[0].workspaceId").value(billing.getId().toString()))
                .andExpect(jsonPath("$.links[0].name").value("billing"))
                .andExpect(jsonPath("$.links[0].projectPath").value("clients/billing-api"))
                .andExpect(jsonPath("$.links[0].origin").value("USER"))
                .andExpect(jsonPath("$.links[0].state").value("CONFIRMED"))
                .andExpect(jsonPath("$.candidates.length()").value(1));
        // Idempotent.
        mockMvc.perform(as(put(linkUrl(aliceA, mfa, billing)), aliceToken)).andExpect(status().isOk());
        assertThat(subjectProjects.findAll()).hasSize(1);

        mockMvc.perform(as(get(url(aliceA, "/project-subjects")), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].workspaceId").value(billing.getId().toString()))
                .andExpect(jsonPath("$[0].subjects[0].name").value("Pilote MFA"));

        mockMvc.perform(as(delete(linkUrl(aliceA, mfa, billing)), aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links.length()").value(0))
                .andExpect(jsonPath("$.candidates.length()").value(2));
        assertThat(subjectProjects.findAll()).singleElement()
                .extracting(RadarSubjectProject::getState).isEqualTo(RadarSubjectProjectState.REFUSED);
        mockMvc.perform(as(get(url(aliceA, "/project-subjects")), aliceToken))
                .andExpect(jsonPath("$.length()").value(0));

        // Un refus n'est jamais reproposé ; mais l'utilisateur peut relier à la main.
        assertThat(service.propose(aliceA, mfa.getId(), billing.getId())).isFalse();
        mockMvc.perform(as(put(linkUrl(aliceA, mfa, billing)), aliceToken))
                .andExpect(jsonPath("$.links[0].origin").value("USER"))
                .andExpect(jsonPath("$.links[0].state").value("CONFIRMED"));
    }

    @Test
    @DisplayName("une proposition se lit comme une question ; la confirmer garde son origine ; la refuser la retient")
    void proposalConfirmedOrRefused() throws Exception {
        RadarSubject mfa = subject(aliceA, "Pilote MFA");
        RadarSubject licences = subject(aliceA, "Licences");
        Workspace billing = project(aliceA, "billing", "billing-api");

        assertThat(service.propose(aliceA, mfa.getId(), billing.getId())).isTrue();
        assertThat(service.propose(aliceA, licences.getId(), billing.getId())).isTrue();
        assertThat(service.propose(aliceA, mfa.getId(), billing.getId())).isFalse();

        mockMvc.perform(as(get(url(aliceA, "/subjects/" + mfa.getId() + "/projects")), aliceToken))
                .andExpect(jsonPath("$.links[0].origin").value("PROPOSED"))
                .andExpect(jsonPath("$.links[0].state").value("PROPOSED"))
                .andExpect(jsonPath("$.candidates.length()").value(0));
        // Une proposition n'est pas un lien : la Forge n'en dit rien.
        mockMvc.perform(as(get(url(aliceA, "/project-subjects")), aliceToken))
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(as(put(linkUrl(aliceA, mfa, billing)), aliceToken))
                .andExpect(jsonPath("$.links[0].origin").value("PROPOSED"))
                .andExpect(jsonPath("$.links[0].state").value("CONFIRMED"));
        mockMvc.perform(as(delete(linkUrl(aliceA, licences, billing)), aliceToken))
                .andExpect(jsonPath("$.links.length()").value(0));

        mockMvc.perform(as(get(url(aliceA, "/project-subjects")), aliceToken))
                .andExpect(jsonPath("$[0].subjects.length()").value(1))
                .andExpect(jsonPath("$[0].subjects[0].id").value(mfa.getId().toString()));
    }

    @Test
    @DisplayName("isolation : un projet d'un autre poste ou d'un autre compte ne se lie pas, un sujet d'un autre poste est introuvable")
    void isolation() throws Exception {
        RadarSubject mfa = subject(aliceA, "Pilote MFA");
        Workspace otherHost = project(aliceB, "cagip-app", "cagip-app");
        Workspace bobs = project(bobScope, "bob-app", "bob-app");
        project(aliceA, "billing", "billing-api");

        mockMvc.perform(as(put(linkUrl(aliceA, mfa, otherHost)), aliceToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(put(linkUrl(aliceA, mfa, bobs)), aliceToken))
                .andExpect(status().isNotFound());
        assertThat(subjectProjects.findAll()).isEmpty();

        mockMvc.perform(as(get(url(aliceA, "/subjects/" + mfa.getId() + "/projects")), aliceToken))
                .andExpect(jsonPath("$.candidates.length()").value(1))
                .andExpect(jsonPath("$.candidates[0].name").value("billing"));
        // Le sujet d'Alice sur le poste B, ou vu par Bob : introuvable.
        mockMvc.perform(as(get(url(aliceB, "/subjects/" + mfa.getId() + "/projects")), aliceToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(get(url(aliceA, "/subjects/" + mfa.getId() + "/projects")), bobToken))
                .andExpect(status().isNotFound());
        // Un terminal n'est pas un projet.
        Workspace terminal = workspaceRepository.save(Workspace.builder().userId(alice.getId())
                .hostId(aliceA.hostId()).name("Terminal Teams").projectPath("").teamsTerminal(true)
                .executionTarget(WorkspaceExecutionTarget.RUNNER).build());
        mockMvc.perform(as(put(linkUrl(aliceA, mfa, terminal)), aliceToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("garde de la Vigie : sans droit 403, client hors de la Vigie 409 ; sujet fusionné : 409")
    void guards() throws Exception {
        RadarSubject mfa = subject(aliceA, "Pilote MFA");
        RadarSubject target = subject(aliceA, "MFA");
        Workspace billing = project(aliceA, "billing", "billing-api");

        User carol = seedUser("carol-radar@example.com", UserRole.USER);
        String carolToken = jwtService.generateToken(carol);
        mockMvc.perform(as(get(url(aliceA, "/project-subjects")), carolToken))
                .andExpect(status().isForbidden());

        hostSpaces.deleteAll(hostSpaces.findAll().stream()
                .filter(s -> s.getHostId().equals(aliceB.hostId()) && s.getSpace() == ClientSpace.VIGIE).toList());
        mockMvc.perform(as(get(url(aliceB, "/project-subjects")), aliceToken))
                .andExpect(status().isConflict());

        subjects.findById(mfa.getId()).ifPresent(s -> {
            s.setMergedIntoId(target.getId());
            subjects.save(s);
        });
        mockMvc.perform(as(put(linkUrl(aliceA, mfa, billing)), aliceToken))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("la purge du Radar d'un poste efface ses liens, pas ceux d'un autre poste")
    void purge() {
        RadarSubject a = subject(aliceA, "Pilote MFA");
        RadarSubject b = subject(aliceB, "Réseau");
        UUID projectA = project(aliceA, "billing", "billing-api").getId();
        UUID projectB = project(aliceB, "net", "net").getId();
        service.link(aliceA, a.getId(), projectA);
        service.link(aliceB, b.getId(), projectB);

        purgeService.purge(aliceA, RadarPurgeReason.USER_REQUEST);

        assertThat(subjectProjects.findAll()).singleElement()
                .extracting(RadarSubjectProject::getHostId).isEqualTo(aliceB.hostId());
    }
}
