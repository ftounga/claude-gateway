package fr.claudegateway.runner.teams;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.runner.ToolContext;
import fr.claudegateway.runner.ToolExecutor;
import fr.claudegateway.runner.ToolOutcome;

/**
 * Les outils Teams du runner (F-87 / SF-87-03). Un seul pour l'instant : {@code teams_status}.
 *
 * <p>Il sert <b>deux</b> consommateurs, et c'est pourquoi il rend du JSON porteur d'une phrase
 * toute faite : l'<b>indicateur</b> de la barre du terminal, qui a besoin d'un état, et l'<b>agent</b>
 * (F-88), qui a besoin de savoir s'il peut lire Teams et de quoi le dire à l'utilisateur.</p>
 *
 * <p><b>Il ne ment jamais par omission.</b> Quand la liaison échoue, le résultat reste un
 * <i>succès</i> d'outil porteur d'un état négatif <b>et de son remède</b> : une erreur d'outil
 * ferait dire à l'agent « je n'ai pas réussi », là où il faut dire « lancez votre navigateur comme
 * ceci ».</p>
 */
public final class TeamsTools implements ToolExecutor {

    /** Nom de l'outil dans le catalogue. */
    public static final String STATUS = "teams_status";

    /** Capacité annoncée dans la trame {@code ready} quand le poste autorise Teams. */
    public static final String CAPABILITY = "teams";

    private final ObjectMapper mapper = new ObjectMapper();
    private final TeamsSession session;
    private final TeamsProbe probe;
    private final BrowserLink.Sleeper sleeper;
    private final boolean enabled;
    private final String disabledReason;
    private volatile boolean firstUseSaid;

    /** Outils actifs, adossés à une session. */
    public TeamsTools(TeamsSession session, BrowserLink.Sleeper sleeper) {
        this.session = session;
        this.probe = new TeamsProbe(session.adapter());
        this.sleeper = sleeper;
        this.enabled = true;
        this.disabledReason = "";
    }

    /** Outils <b>refusés</b> sur cette machine ({@code --no-teams}) : l'état le dit, et pourquoi. */
    public static TeamsTools disabled(String reason) {
        return new TeamsTools(reason);
    }

    private TeamsTools(String reason) {
        this.session = null;
        this.probe = null;
        this.sleeper = null;
        this.enabled = false;
        this.disabledReason = reason == null || reason.isBlank()
                ? "Le volet Teams est désactivé sur cette machine." : reason.strip();
    }

    /** Vrai si la capacité {@code teams} doit être annoncée à la gateway. */
    public boolean enabled() {
        return enabled;
    }

    @Override
    public ToolOutcome execute(String tool, JsonNode input, ToolContext context) {
        if (!STATUS.equals(tool)) {
            return ToolOutcome.error("unsupported_tool", "Outil Teams inconnu : " + tool);
        }
        if (!enabled) {
            return ToolOutcome.ok(render(new TeamsProbeResult(TeamsLinkState.BROWSER_NOT_DETECTED,
                    TeamsHealth.full(0), 0, "", disabledReason), ""));
        }
        // D3 : l'annonce de premier usage voyage avec le PREMIER résultat, et une seule fois. Elle
        // est dite sur la console par la session ; ici, elle est écrite là où l'utilisateur regarde.
        String firstUse = firstUseSaid ? "" : session.notice().text();
        firstUseSaid = true;
        TeamsProbeResult result;
        try {
            result = probe.probe(session.link(), sleeper);
        } catch (BrowserLinkException e) {
            result = TeamsProbe.notLinked(e);
        } catch (RuntimeException e) {
            result = new TeamsProbeResult(TeamsLinkState.BROWSER_NOT_DETECTED, TeamsHealth.full(0),
                    0, "", "La liaison au navigateur n'a pas abouti sur cette machine.");
        }
        return ToolOutcome.ok(render(result, firstUse));
    }

    /**
     * Le JSON rendu. Le champ {@code text} porte la <b>phrase complète</b> : c'est lui que l'agent
     * répète, et c'est lui qui évite que deux consommateurs réinventent chacun leur formulation.
     */
    String render(TeamsProbeResult result, String firstUse) {
        ObjectNode node = mapper.createObjectNode();
        node.put("state", result.state().name());
        node.put("label", result.state().label());
        node.put("sentence", result.sentence());
        node.put("remedy", result.remedy());
        node.put("browser", result.browser());
        node.put("observedResponses", result.observed());
        node.put("conclusive", result.conclusive());
        node.put("adapter", enabled ? session.adapter().version() : "");

        ObjectNode health = node.putObject("health");
        health.put("verdict", result.health().verdict().name());
        health.put("recognizedFields", result.health().recognizedFields());
        health.put("expectedFields", result.health().expectedFields());
        ArrayNode missing = health.putArray("missingFields");
        result.health().missingFields().forEach(missing::add);
        ArrayNode versions = health.putArray("observedApiVersions");
        result.health().observedApiVersions().forEach(versions::add);

        StringBuilder text = new StringBuilder(result.sentence());
        if (!result.remedy().isEmpty()) {
            text.append(System.lineSeparator()).append(result.remedy());
        }
        if (firstUse != null && !firstUse.isBlank()) {
            text.append(System.lineSeparator()).append(firstUse);
        }
        node.put("text", text.toString());
        return node.toString();
    }
}
