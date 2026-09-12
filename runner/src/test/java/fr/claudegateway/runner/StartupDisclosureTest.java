package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * F-57 / SF-57-01 — la déclaration de démarrage : ce que le runner fait, sous quels droits, par
 * quelle route, et ce qu'il ne cherche pas.
 *
 * <p>Ces tests gardent aussi la <b>ligne rouge</b> de F-57 : le bloc décrit la configuration du
 * runner, jamais l'état du poste, et il n'affiche jamais un identifiant de proxy.</p>
 */
class StartupDisclosureTest {

    private static String block(String user, boolean elevated, Map<String, String> env) {
        List<String> lines = StartupDisclosure.lines(Privileges.of(user, elevated),
                ProxyResolver.fromEnv(env).route());
        return String.join("\n", lines);
    }

    private static List<String> lines(String user, boolean elevated, Map<String, String> env) {
        return StartupDisclosure.lines(Privileges.of(user, elevated),
                ProxyResolver.fromEnv(env).route());
    }

    @Test
    void it_says_what_the_runner_does_before_anything_else() {
        String text = block("francky", false, Map.of());

        assertTrue(text.contains("Ce runner :"), text);
        assertTrue(text.contains("exécute sur cette machine les commandes que vous autorisez"), text);
        // La porte de confirmation est ce qui protège réellement — et depuis F-73, la seule chose.
        assertTrue(text.contains("sans votre geste"), text);
    }

    // ------------------------------------------------------------------ F-73 / SF-73-01

    @Test
    void it_states_its_scope_before_naming_the_account() {
        List<String> lines = lines("francky", false, Map.of());

        assertEquals(5, lines.size(), lines.toString());
        assertTrue(lines.get(1).startsWith("Portée    :"), lines.toString());
        assertTrue(lines.get(2).startsWith("Compte    :"), lines.toString());
    }

    @Test
    void the_scope_line_says_there_is_no_folder_restriction_and_what_that_means() {
        String text = block("francky", false, Map.of());

        assertTrue(text.contains("aucune restriction de dossier"), text);
        assertTrue(text.contains("point de départ"), text);
        // Les fichiers sensibles sont nommés : c'est le risque que le product owner a assumé,
        // et l'assumer suppose de le dire (F-73, D8).
        assertTrue(text.contains(".env"), text);
        assertTrue(text.contains("clés SSH"), text);
        assertTrue(text.contains(".aws/"), text);
        assertTrue(text.contains("part chez le fournisseur"), text);
    }

    @Test
    void the_block_never_promises_a_confinement_it_does_not_have() {
        String text = block("francky", false, Map.of());

        assertFalse(text.contains("confiné"), text);
        assertFalse(text.contains("dans le dossier du projet visé"), text);
    }

    @Test
    void the_block_informs_without_alarming() {
        // F-73, D6 : c'est sa machine, il a lancé ce programme lui-même. On informe, on n'effraie
        // pas — un bloc alarmiste se saute, et sauter ce bloc est exactement ce qu'on ne veut pas.
        String text = block("francky", false, Map.of()).toLowerCase(java.util.Locale.ROOT);

        for (String cri : new String[] {"attention", "danger", "alerte", "risque", "!"}) {
            assertFalse(text.contains(cri), "mot alarmiste « " + cri + " » : " + text);
        }
    }

    @Test
    void it_names_the_screen_the_user_actually_sees() {
        // F-58 / SF-58-01 — le runner parle à l'utilisateur : il doit citer l'écran par le nom qui
        // s'y affiche. « Atelier » n'est plus ce nom.
        String text = block("francky", false, Map.of());

        assertTrue(text.contains("depuis la Forge"), text);
        assertFalse(text.contains("Atelier"), text);
    }

    @Test
    void it_names_the_account_it_runs_under() {
        String text = block("francky", false, Map.of());

        assertTrue(text.contains("Compte    : francky"), text);
        assertFalse(text.contains("administrateur"),
                "un compte ordinaire ne doit pas être annoncé comme administrateur : " + text);
    }

    @Test
    void an_elevated_account_is_announced_as_such() {
        String text = block("root", true, Map.of());

        assertTrue(text.contains("(administrateur)"), text);
    }

    @Test
    void a_missing_account_name_does_not_break_the_block() {
        String text = block(null, false, Map.of());

        assertTrue(text.contains(StartupDisclosure.UNKNOWN_ACCOUNT), text);
    }

    @Test
    void without_a_declared_proxy_the_route_is_direct() {
        String text = block("francky", false, Map.of());

        assertTrue(text.contains("Route     : directe"), text);
        assertTrue(text.contains("aucun proxy déclaré dans ce terminal"), text);
    }

    @Test
    void a_corporate_proxy_is_named_with_the_variable_that_decided_it() {
        String text = block("francky", false, Map.of("HTTPS_PROXY", "http://proxy.corp:3128"));

        assertTrue(text.contains("proxy d'entreprise proxy.corp:3128"), text);
        // La variable est citée : c'est exactement ce qu'il faut modifier pour changer la route.
        assertTrue(text.contains("HTTPS_PROXY"), text);
    }

    @Test
    void the_variable_named_is_the_one_actually_read() {
        String text = block("francky", false, Map.of("HTTP_PROXY", "http://proxy.corp:8080"));

        assertTrue(text.contains("HTTP_PROXY"), text);
        assertFalse(text.contains("HTTPS_PROXY"),
                "citer une variable non renseignée enverrait modifier la mauvaise : " + text);
    }

    @Test
    void a_loopback_proxy_is_described_as_a_local_relay_not_as_the_company() {
        String text = block("francky", false, Map.of("HTTPS_PROXY", "http://127.0.0.1:3128"));

        assertTrue(text.contains("relais local 127.0.0.1:3128"), text);
        assertTrue(text.contains("porte l'authentification à la place du runner"), text);
        assertFalse(text.contains("proxy d'entreprise"),
                "un relais lancé par l'utilisateur n'est pas le proxy de l'entreprise : " + text);
    }

    @Test
    void localhost_is_a_local_relay_too() {
        String text = block("francky", false, Map.of("HTTPS_PROXY", "http://localhost:3128"));

        assertTrue(text.contains("relais local localhost:3128"), text);
    }

    @Test
    void proxy_credentials_are_never_shown() {
        String text = block("francky", false,
                Map.of("HTTPS_PROXY", "http://DOMAINE%5Cfrancky:S3cr3t@proxy.corp:8080"));

        assertTrue(text.contains("proxy.corp:8080"), text);
        assertFalse(text.contains("S3cr3t"),
                "un écran de transparence qui divulgue un mot de passe est une régression : " + text);
        assertFalse(text.contains("DOMAINE"), text);
        assertFalse(text.contains("@"), text);
    }

    @Test
    void a_malformed_proxy_value_still_produces_a_block() {
        String text = block("francky", false, Map.of("HTTPS_PROXY", "proxy.corp:pas-un-port"));

        assertTrue(text.contains("Route     :"), text);
        assertTrue(text.contains("proxy.corp:pas-un-port"), text);
    }

    @Test
    void it_carries_the_employer_logging_reminder() {
        String text = block("francky", false, Map.of());

        assertTrue(text.contains("vraisemblablement"),
                "le produit ne SAIT pas que ce poste est journalisé ; il ne doit pas l'affirmer : "
                        + text);
        assertTrue(text.contains("journalisées"), text);
        assertTrue(text.contains("votre employeur"), text);
    }

    @Test
    void it_states_that_the_runner_does_not_look_for_what_watches_the_machine() {
        String text = block("francky", false, Map.of());

        // C'est la promesse de F-57, et elle est écrite noir sur blanc à celui qui lance le runner.
        assertTrue(text.contains("ne cherche pas à savoir ce qui observe ce"), text);
        assertTrue(text.contains("ne le fera pas"), text);
    }
}
