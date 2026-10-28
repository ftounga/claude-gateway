package fr.claudegateway.atelier.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.atelier.journey.ChantierPreflight.Code;
import fr.claudegateway.atelier.journey.ChantierUnblocker.Mark;
import fr.claudegateway.atelier.journey.ChantierUnblocker.Who;

/** F-219 / SF-219-06 — qui exécute l'étape : plan, « qui débloque ? », préflight, note de tour, consigne. */
class StepActorTest {

    private static JourneyPlan toolInput(String json) {
        try {
            return JourneyPlan.fromToolInput(new ObjectMapper().readTree(json));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** L'incident agenor : 17 revérifiée, 18 = envoyer le message à Dhaou (le PO seul), 19 faisable. */
    private static final String AGENOR = "[{\"title\":\"Revérifier la zone\",\"risk\":\"LECTURE\",\"actor\":\"OUTIL\"},"
            + "{\"title\":\"Envoyer le message DNS on-prem à Dhaou\",\"risk\":\"EXTERNE\",\"actor\":\"MOI\","
            + "\"depends_on\":[1]},"
            + "{\"title\":\"Mettre à jour le runbook\",\"risk\":\"NOTES\",\"actor\":\"OUTIL\",\"depends_on\":[1]}]";

    private static ChantierPreflight preflight(JourneyPlan plan) {
        return ChantierPreflight.evaluate(new ChantierPreflight.Input(7, true, JourneyPhase.EXECUTION, plan.toJson(),
                3, 3, Map.of(), false), Map.of());
    }

    @Test
    @DisplayName("plan d'avant SF-219-06 : acteur non précisé, vaut l'outil, réécrit à l'octet près")
    void legacyPlanUnchanged() {
        String legacy = "[{\"title\":\"A\",\"risk\":\"REVERSIBLE\",\"verify\":null,\"rollback\":null,"
                + "\"waitsOn\":null,\"status\":\"A_FAIRE\",\"evidence\":null}]";
        JourneyPlan plan = JourneyPlan.fromJson(legacy);
        assertThat(plan.steps().get(0).actor()).isNull();
        assertThat(plan.steps().get(0).effectiveActor()).isEqualTo(JourneyPlan.Actor.OUTIL);
        assertThat(plan.toJson()).isEqualTo(legacy);
    }

    @Test
    @DisplayName("l'acteur se lit du modèle (actor ou acteur), se garde au JSON ; une valeur inconnue est refusée")
    void actorFromToolInput() {
        JourneyPlan plan = toolInput(AGENOR);
        assertThat(plan.steps().get(1).actor()).isEqualTo(JourneyPlan.Actor.MOI);
        assertThat(JourneyPlan.fromJson(plan.toJson()).steps().get(1).actor()).isEqualTo(JourneyPlan.Actor.MOI);
        assertThat(toolInput("[{\"title\":\"A\",\"risk\":\"LECTURE\",\"acteur\":\"tiers\"}]").steps().get(0).actor())
                .isEqualTo(JourneyPlan.Actor.TIERS);
        assertThatThrownBy(() -> toolInput("[{\"title\":\"A\",\"risk\":\"LECTURE\",\"actor\":\"PERSONNE\"}]"))
                .isInstanceOf(InvalidJourneyException.class).hasMessageContaining("OUTIL, MOI ou TIERS");
        // Persistance illisible : jamais d'exception, non précisé.
        assertThat(JourneyPlan.fromJson("[{\"title\":\"A\",\"actor\":\"??\"}]").steps().get(0).actor()).isNull();
    }

    @Test
    @DisplayName("withActor change l'acteur sans toucher l'état ; un changement d'acteur est un changement de contenu")
    void withActor() {
        JourneyPlan plan = toolInput(AGENOR).withStep(0, JourneyPlan.StepStatus.FAIT, null);
        JourneyPlan requalified = plan.withActor(0, JourneyPlan.Actor.TIERS);
        assertThat(requalified.steps().get(0).status()).isEqualTo(JourneyPlan.StepStatus.FAIT);
        assertThat(requalified.steps().get(0).actor()).isEqualTo(JourneyPlan.Actor.TIERS);
        assertThat(requalified.changedSince(plan, 0)).isTrue();
    }

    @Test
    @DisplayName("qui débloque : MOI → votre geste, TIERS → on attend quelqu'un, la suite hérite, faite → rien")
    void unblocker() {
        JourneyPlan plan = toolInput("[{\"title\":\"Envoyer le message à Dhaou\",\"risk\":\"EXTERNE\",\"actor\":\"MOI\"},"
                + "{\"title\":\"Appliquer la zone\",\"risk\":\"EXTERNE\",\"actor\":\"OUTIL\"},"
                + "{\"title\":\"Valider le CAB\",\"risk\":\"EXTERNE\",\"actor\":\"TIERS\",\"depends_on\":[1]}]");
        List<Mark> marks = ChantierUnblocker.steps(plan, Map.of());
        assertThat(marks.get(0).who()).isEqualTo(Who.MOI);
        assertThat(marks.get(0).detail()).contains("Envoyer le message à Dhaou").contains("à faire par vous");
        assertThat(marks.get(1).who()).isEqualTo(Who.MOI); // hérite de l'étape 1
        assertThat(marks.get(1).step()).isEqualTo(1);
        assertThat(marks.get(2).who()).isEqualTo(Who.AUTRUI); // sa propre cause, avant l'héritage
        assertThat(marks.get(2).step()).isEqualTo(3);
        assertThat(ChantierUnblocker.steps(plan.withStep(0, JourneyPlan.StepStatus.FAIT, null), Map.of()).get(0))
                .isNull();
        // Une attente ouverte prime sur l'acteur.
        JourneyPlan waiting = toolInput("[{\"title\":\"A\",\"risk\":\"EXTERNE\",\"actor\":\"MOI\",\"waits_on\":\"k\"}]");
        assertThat(ChantierUnblocker.steps(waiting, Map.of("k", new WaitInfo("DEMANDE", "CLIENT", "Gino", null)))
                .get(0).waitingOn()).isEqualTo("Gino");
    }

    @Test
    @DisplayName("préflight strict : une étape MOI refuse le départ, avec son code et son titre ; avancer la contourne")
    void preflight() {
        ChantierPreflight p = preflight(toolInput(AGENOR));
        assertThat(p.ready()).isFalse();
        assertThat(p.reasons()).extracting(ChantierPreflight.Reason::code).containsExactly(Code.STEP_ACTOR_MOI);
        assertThat(p.reasons().get(0).label()).contains("Envoyer le message DNS on-prem à Dhaou");
        assertThat(p.reasons().get(0).step()).isEqualTo(2);
        assertThat(p.advance().possible()).isTrue();
        assertThat(p.advance().toolSteps()).containsExactly(1, 3);
        assertThat(p.advance().remaining()).extracting(Mark::step).containsExactly(2);

        JourneyPlan tiers = toolInput("[{\"title\":\"A\",\"risk\":\"LECTURE\",\"actor\":\"TIERS\"}]");
        assertThat(preflight(tiers).reasons()).extracting(ChantierPreflight.Reason::code)
                .containsExactly(Code.STEP_ACTOR_TIERS);
        assertThat(preflight(tiers).advance().possible()).isFalse();
    }

    @Test
    @DisplayName("plan sans acteur, ou étape MOI déjà faite : le préflight est inchangé")
    void preflightUnchangedWithoutActor() {
        JourneyPlan legacy = toolInput("[{\"title\":\"A\",\"risk\":\"LECTURE\"},{\"title\":\"B\",\"risk\":\"EXTERNE\"}]");
        assertThat(preflight(legacy).ready()).isTrue();
        JourneyPlan done = toolInput(AGENOR).withStep(1, JourneyPlan.StepStatus.FAIT, "envoyé par le PO");
        assertThat(preflight(done).ready()).isTrue();
    }

    private static SubjectJourney guided(String planJson) {
        SubjectJourney j = SubjectJourney.builder().userId(UUID.randomUUID()).workspaceId(UUID.randomUUID())
                .mode(JourneyMode.GUIDE).phase(JourneyPhase.EXECUTION).build();
        j.setChantierNumber(7);
        j.setPlanJson(planJson);
        j.setPlanVersion(3);
        j.setValidatedVersion(3);
        return j;
    }

    @Test
    @DisplayName("note de tour : l'acteur des étapes non-outil ; invite à préciser tant qu'une étape restante n'en a pas")
    void turnNote() {
        String note = JourneyTurnNote.render(guided(toolInput(AGENOR).toJson()));
        assertThat(note).contains("Envoyer le message DNS on-prem à Dhaou · externe / irréversible · par vous")
                .doesNotContain("par l'outil")
                .doesNotContain("ACTEURS À PRÉCISER");

        JourneyPlan legacy = toolInput("[{\"title\":\"A\",\"risk\":\"LECTURE\"},{\"title\":\"B\",\"risk\":\"EXTERNE\"},"
                + "{\"title\":\"C\",\"risk\":\"EXTERNE\",\"actor\":\"MOI\"}]").withStep(0, JourneyPlan.StepStatus.VERIFIE,
                "ok");
        SubjectJourney j = guided(legacy.toJson());
        String invite = JourneyTurnNote.render(j);
        assertThat(invite).contains("ACTEURS À PRÉCISER — chantier 7 : étape 2.").contains("update_plan_step")
                .contains("sans status");
        assertThat(invite.indexOf("ACTEURS À PRÉCISER")).isEqualTo(invite.lastIndexOf("ACTEURS À PRÉCISER"));

        j.setOtherOpenChantiers(List.of(SubjectJourneyChantier.builder().number(2).status("OPEN")
                .phase(JourneyPhase.PLAN).planJson("[{\"title\":\"X\",\"risk\":\"LECTURE\"}]").build()));
        assertThat(JourneyTurnNote.render(j)).contains("chantier 7 : étape 2 ; chantier 2 : étape 1.");
    }

    @Test
    @DisplayName("consigne : la règle d'acteur est dans le GUIDE stable ; actor requis au plan, status facultatif à l'étape")
    void catalog() {
        assertThat(JourneyToolCatalog.GUIDE).contains("QUI EXÉCUTE").contains("MOI").contains("TIERS")
                .contains("email_me n'écrit qu'à l'utilisateur").contains("ne poste pas de message");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) JourneyToolCatalog.setPlanDefinition().inputSchema()
                .get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> step = (Map<String, Object>) ((Map<String, Object>) props.get("steps")).get("items");
        assertThat(step.get("required")).isEqualTo(List.of("title", "risk", "actor"));
        AgentTool update = JourneyToolCatalog.updateStepDefinition();
        assertThat(update.inputSchema().get("required")).isEqualTo(List.of("step"));
        assertThat(((Map<?, ?>) update.inputSchema().get("properties")).containsKey("actor")).isTrue();
    }
}
