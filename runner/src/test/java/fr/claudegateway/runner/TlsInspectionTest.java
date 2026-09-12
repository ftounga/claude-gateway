package fr.claudegateway.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * F-57 / SF-57-02 — le verdict d'interception TLS.
 *
 * <p>Ces tests protègent surtout contre le <b>faux positif</b> : annoncer une interception là où il
 * n'y en a pas détruirait la confiance dans tout ce que le runner affiche par ailleurs. Le faux
 * négatif, lui, est assumé (cadrage, décision 2) — le silence est toujours le comportement sûr.</p>
 */
class TlsInspectionTest {

    private static final String PUBLIC_ROOT = "CN=ISRG Root X1, O=Internet Security Research Group, C=US";
    private static final String PUBLIC_INTERMEDIATE = "CN=R11, O=Let's Encrypt, C=US";
    private static final String SITE = "CN=portal.ng-itconsulting.com";
    private static final String CORPORATE_ROOT = "CN=Acme Corp Proxy CA, OU=Securite, O=Acme, C=FR";

    private static TlsInspection.ChainLink link(String subject, String issuer) {
        return new TlsInspection.ChainLink(subject, issuer);
    }

    @Test
    void a_chain_rooted_on_a_public_authority_says_nothing() {
        List<TlsInspection.ChainLink> chain = List.of(
                link(SITE, PUBLIC_INTERMEDIATE),
                link(PUBLIC_INTERMEDIATE, PUBLIC_ROOT));

        assertEquals(Optional.empty(),
                TlsInspection.interceptingRoot(chain, Set.of(PUBLIC_ROOT)));
    }

    @Test
    void a_chain_whose_last_link_IS_a_public_root_says_nothing() {
        // Chaîne croisée : le serveur envoie la racine elle-même. Ne regarder que l'émetteur du
        // dernier maillon — la racine se signe elle-même, donc « racine inconnue » — crierait à tort.
        List<TlsInspection.ChainLink> chain = List.of(
                link(SITE, PUBLIC_INTERMEDIATE),
                link(PUBLIC_INTERMEDIATE, PUBLIC_ROOT),
                link(PUBLIC_ROOT, "CN=Vieille racine heritee, O=Autrefois"));

        assertEquals(Optional.empty(),
                TlsInspection.interceptingRoot(chain, Set.of(PUBLIC_ROOT)));
    }

    @Test
    void a_chain_re_signed_by_an_appliance_names_its_root() {
        List<TlsInspection.ChainLink> chain = List.of(
                link(SITE, "CN=Acme Corp Proxy Issuing CA, O=Acme"),
                link("CN=Acme Corp Proxy Issuing CA, O=Acme", CORPORATE_ROOT));

        Optional<String> root = TlsInspection.interceptingRoot(chain, Set.of(PUBLIC_ROOT));

        assertTrue(root.isPresent(), "une racine absente des racines publiques doit être signalée");
        assertEquals(CORPORATE_ROOT, root.get());
    }

    @Test
    void case_and_spacing_differences_are_not_an_interception() {
        List<TlsInspection.ChainLink> chain = List.of(
                link(SITE, "cn=isrg root x1,o=internet security research group,c=us"));

        assertEquals(Optional.empty(), TlsInspection.interceptingRoot(chain, Set.of(PUBLIC_ROOT)),
                "deux encodages du même DN ne doivent pas produire un faux positif");
    }

    @Test
    void an_empty_chain_says_nothing() {
        assertEquals(Optional.empty(), TlsInspection.interceptingRoot(List.of(), Set.of(PUBLIC_ROOT)));
        assertEquals(Optional.empty(), TlsInspection.interceptingRoot(null, Set.of(PUBLIC_ROOT)));
    }

    @Test
    void without_a_reference_of_public_roots_nothing_can_be_claimed() {
        List<TlsInspection.ChainLink> chain = List.of(link(SITE, CORPORATE_ROOT));

        assertEquals(Optional.empty(), TlsInspection.interceptingRoot(chain, Set.of()),
                "magasin illisible : se taire, jamais affirmer");
        assertEquals(Optional.empty(), TlsInspection.interceptingRoot(chain, null));
    }

    @Test
    void malformed_links_do_not_throw() {
        List<TlsInspection.ChainLink> chain = Arrays.asList(
                null, link(null, null), link(SITE, "   "));

        assertEquals(Optional.empty(), TlsInspection.interceptingRoot(chain, Set.of(PUBLIC_ROOT)));
    }

    @Test
    void the_common_name_is_what_the_console_shows() {
        assertEquals("Acme Corp Proxy CA", TlsInspection.commonName(CORPORATE_ROOT));
        assertEquals("Acme Corp Proxy CA", TlsInspection.commonName("cn=Acme Corp Proxy CA, O=Acme"));
        // Sans CN, le DN entier vaut mieux qu'une chaîne vide.
        assertEquals("O=Acme, C=FR", TlsInspection.commonName("O=Acme, C=FR"));
        assertEquals("", TlsInspection.commonName(null));
    }

    @Test
    void the_message_calls_it_normal_and_promises_not_to_bypass_it() {
        String text = TlsInspection.message("portal.ng-itconsulting.com", CORPORATE_ROOT);

        assertTrue(text.contains("portal.ng-itconsulting.com"), text);
        assertTrue(text.contains("Acme Corp Proxy CA"), text);
        assertFalse(text.contains("OU=Securite"), "le DN brut est illisible en console : " + text);
        // Un proxy d'inspection n'est pas une attaque : le message le dit, sinon il alarme à tort.
        assertTrue(text.contains("fonctionnement normal"), text);
        // Et la promesse de F-57, écrite : afficher, jamais contourner.
        assertTrue(text.contains("ne le contourne pas"), text);
        assertTrue(text.contains("ne relâche aucune vérification"), text);
    }

    // ------------------------------------------------------------------ F-80 / SF-80-01

    @Test
    void the_organisation_is_read_from_the_presented_certificate() {
        assertEquals("Zscaler Inc.",
                TlsInspection.organisation("CN=portal.ng-itconsulting.com, O=Zscaler Inc."));
        assertEquals("Zscaler Inc.",
                TlsInspection.organisation("cn=portal.x.fr, o=Zscaler Inc., C=US"));
        // Sans O=, rien : mieux vaut un nom incomplet qu'un nom inventé.
        assertEquals("", TlsInspection.organisation("CN=portal.x.fr"));
        assertEquals("", TlsInspection.organisation(null));
    }

    @Test
    void the_presenter_names_the_editor_first_then_the_authority() {
        // L'ordre est celui de la reconnaissance : « Zscaler Inc. » se lit d'un coup d'œil, le CN
        // de l'autorité intermédiaire demande de savoir ce qu'est une autorité intermédiaire.
        String presenter = TlsInspection.presenter(
                List.of(link("CN=portal.ng-itconsulting.com, O=Zscaler Inc.", CORPORATE_ROOT)),
                "CN=Zscaler Intermediate Root CA (zscaler.net) (t)");

        assertEquals("Zscaler Inc. (CN=Zscaler Intermediate Root CA (zscaler.net) (t))", presenter);
    }

    @Test
    void the_presenter_falls_back_to_the_authority_alone() {
        assertEquals("Acme Corp Proxy CA",
                TlsInspection.presenter(List.of(link("CN=portal.x.fr", CORPORATE_ROOT)),
                        CORPORATE_ROOT));
        assertEquals("Acme Corp Proxy CA", TlsInspection.presenter(null, CORPORATE_ROOT));
        assertEquals("Acme Corp Proxy CA", TlsInspection.presenter(List.of(), CORPORATE_ROOT));
    }

    @Test
    void the_handshake_failure_message_names_the_presenter_and_calls_it_normal() {
        String text = TlsInspection.handshakeFailure("Zscaler Inc. (CN=Zscaler Root CA)");

        assertTrue(text.contains("Certificat présenté par : Zscaler Inc."), text);
        assertTrue(text.contains("déchiffre le trafic et le re-signe"), text);
        assertTrue(text.contains("normal d'un proxy d'inspection d'entreprise"), text);
        // Aucun remède ici : celui-ci appartient au message de la panne, affiché juste au-dessus.
        assertFalse(text.contains("trustStore"), text);
    }
}
