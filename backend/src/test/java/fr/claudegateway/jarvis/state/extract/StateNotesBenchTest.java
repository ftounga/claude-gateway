package fr.claudegateway.jarvis.state.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-247 / SF-247-14 — <b>banc NOTES</b> : 10 sorties non couvertes et, pour chacune, des faits tels qu'un modèle
 * pourrait les rendre — justes, déduits, reformulés, inventés, secrets. Porte : <b>0 fait sans citation vérifiée</b>.
 */
class StateNotesBenchTest {

    private static final List<StateNotesGuard.Output> OUTPUTS = List.of(
            new StateNotesGuard.Output(1, "psql -c 'select etat from lots where id=42'", " etat \n------\n EN_COURS\n(1 row)"),
            new StateNotesGuard.Output(2, "curl -s https://api.interne/sante", "service paiement : OK (v3.2.1)"),
            new StateNotesGuard.Output(3, "cat VERSION", "4.18.0"),
            new StateNotesGuard.Output(4, "mvn -v", "Apache Maven 3.9.6"),
            new StateNotesGuard.Output(5, "kubectl get cm app -o yaml", "data:\n  release: 2026.11.2\n  mode: blue"),
            new StateNotesGuard.Output(6, "curl -s https://ci.interne/job/42", "Job 42 : FAILED à l'étape tests"),
            new StateNotesGuard.Output(7, "cat .env", "API_TOKEN=glpat-abcdefghijklmnopqrstuvwx"),
            new StateNotesGuard.Output(8, "jq .version package.json", "\"1.4.0\""),
            new StateNotesGuard.Output(9, "curl -s https://wiki/page/77", "Page « Runbook » révision 12, modifiée hier"),
            new StateNotesGuard.Output(10, "sqlcmd -Q schema", "schema_version = 87"));

    private static String fact(int n, String objet, String famille, String attribut, String valeur, String citation) {
        return "{\"sortie\":" + n + ",\"objet\":\"" + objet + "\",\"famille\":\"" + famille + "\",\"attribut\":\""
                + attribut + "\",\"valeur\":\"" + valeur + "\",\"citation\":\"" + citation + "\"}";
    }

    @Test
    @DisplayName("Banc NOTES : seuls les faits prouvés mot pour mot passent ; 0 fait sans citation vérifiée")
    void bench() {
        String raw = "{\"faits\":[" + String.join(",",
                // justes (5)
                fact(1, "etat", "OTHER", "state", "EN_COURS", " EN_COURS"),
                fact(2, "service paiement", "DEPLOYMENT", "version", "v3.2.1", "service paiement : OK (v3.2.1)"),
                fact(5, "release", "RELEASE", "version", "2026.11.2", "release: 2026.11.2"),
                fact(6, "Job 42", "PIPELINE", "result", "FAILED", "Job 42 : FAILED"),
                fact(10, "schema_version", "DB_SCHEMA", "schema_version", "87", "schema_version = 87"),
                // déduit : la valeur n'est pas dans la citation
                fact(2, "service paiement", "DEPLOYMENT", "health", "sain", "service paiement : OK"),
                // reformulé : la citation n'est pas mot pour mot
                fact(9, "Runbook", "PAGE", "page_version", "12", "Page Runbook, révision 12"),
                // inventé : objet absent de la sortie
                fact(3, "api-gateway", "RELEASE", "version", "4.18.0", "4.18.0"),
                // mauvaise sortie
                fact(4, "maven", "OTHER", "version", "3.9.6", "service paiement : OK (v3.2.1)"),
                // secret
                fact(7, "API_TOKEN", "OTHER", "value", "glpat-abcdefghijklmnopqrstuvwx",
                        "API_TOKEN=glpat-abcdefghijklmnopqrstuvwx"),
                // famille hors vocabulaire / ACCESS interdit
                fact(8, "version", "PACKAGE", "version", "1.4.0", "\\\"1.4.0\\\""),
                fact(8, "version", "ACCESS", "version", "1.4.0", "\\\"1.4.0\\\""),
                // sortie inexistante
                fact(11, "x", "OTHER", "value", "x", "xxxx")) + "]}";

        StateNotesGuard.Verdict v = StateNotesGuard.check(raw, OUTPUTS);

        assertThat(v.kept()).hasSize(5);
        assertThat(v.rejected()).isEqualTo(8);
        for (StateNotesGuard.Fact f : v.kept()) {
            StateNotesGuard.Output o = OUTPUTS.get(f.output() - 1);
            assertThat(o.body()).contains(f.value());
            assertThat(f.key()).startsWith("NOTE:");
            assertThat(SecretFilter.looksSecret(f.value())).isFalse();
        }
        assertThat(v.kept()).extracting(StateNotesGuard.Fact::key).contains("NOTE:service-paiement", "NOTE:Job-42");
    }

    @Test
    @DisplayName("Sortie illisible ou vide : aucun fait, rien de compté comme rejeté")
    void unreadable() {
        assertThat(StateNotesGuard.check("Voici les faits : EN_COURS", OUTPUTS).kept()).isEmpty();
        assertThat(StateNotesGuard.check("{\"faits\":[]}", OUTPUTS).kept()).isEmpty();
        assertThat(StateNotesGuard.check(null, OUTPUTS).kept()).isEmpty();
    }

    @Test
    @DisplayName("Entrée bornée : ≈ 8 000 jetons, une sortie trop longue est écartée")
    void bounded() {
        List<StateExtractJobHandler.Uncovered> many = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            many.add(new StateExtractJobHandler.Uncovered("t" + i, "cmd", "x".repeat(7_000), null));
        }
        many.add(new StateExtractJobHandler.Uncovered("big", "cmd", "y".repeat(9_000), null));
        List<StateNotesGuard.Output> out = StateNotesJobHandler.outputs(many);
        assertThat(out.stream().mapToInt(o -> o.body().length()).sum()).isLessThanOrEqualTo(
                StateNotesGuard.MAX_INPUT_CHARS);
        assertThat(out).noneMatch(o -> o.body().startsWith("y"));
    }
}
