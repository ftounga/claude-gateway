package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * F-108 / SF-108-07 — <b>les droits d'un élément de drive</b>, lus sur la forme RÉELLE de l'API v2.1
 * (relevé catalogue CAGIP 2026-09-16). Ce que ces tests garantissent : la lecture par nom de champ,
 * le refus <b>nommé</b> quand le téléchargement n'est pas permis, et qu'aucune adresse signée ni aucun
 * autre champ ne franchit la couche.
 */
class SharePointItemAccessTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Forme réelle v2.1 : webUrl et accessViewpoint / irmCapabilities lus par leur nom")
    void reads_the_real_drive_item_form() {
        SharePointItemAccess access = SharePointItemAccess.parse(
                TeamsSamples.read("sharepoint-driveitem-cagip.json"),
                TeamsSamples.read("sharepoint-driveitem-labelpolicies-cagip.json"));

        assertEquals("https://cagip-my.sharepoint.com/personal/paul_cagip_invalid/Documents/"
                + "Recordings/Comite-IAM.mp4", access.webUrl());
        assertTrue(access.hasAccessViewpoint());
        assertTrue(access.canRead());
        assertTrue(access.canDownload());
        assertFalse(access.canDelete());
        assertTrue(access.hasIrm());
        assertTrue(access.irmCanExtract());
        assertTrue(access.downloadable(), "canDownload=true + canExtract=true → téléchargeable");
        assertTrue(access.downloadRefusal().isEmpty());
    }

    @Test
    @DisplayName("canDownload=false → refus NOMMÉ (droits / politique du tenant), jamais un contournement")
    void no_download_right_is_a_named_refusal() {
        ObjectNode item = mapper.createObjectNode();
        item.put("webUrl", "https://cagip-my.sharepoint.com/personal/x/Recordings/reunion.mp4");
        ObjectNode av = item.putObject("accessViewpoint");
        av.put("canRead", true).put("canDownload", false);

        SharePointItemAccess access = SharePointItemAccess.parse(item, null);

        assertFalse(access.downloadable());
        assertEquals(SharePointItemAccess.DOWNLOAD_DENIED, access.downloadRefusal());
        assertTrue(access.downloadRefusal().contains("canDownload"), access.downloadRefusal());
    }

    @Test
    @DisplayName("Protection IRM canExtract=false → refus NOMMÉ, même si canDownload est vrai")
    void irm_no_extract_is_a_named_refusal() {
        ObjectNode item = mapper.createObjectNode();
        item.putObject("accessViewpoint").put("canDownload", true);
        ObjectNode policies = mapper.createObjectNode();
        policies.putObject("irmCapabilities").put("canExtract", false).put("canRead", true);

        SharePointItemAccess access = SharePointItemAccess.parse(item, policies);

        assertFalse(access.downloadable());
        assertEquals(SharePointItemAccess.EXTRACT_DENIED, access.downloadRefusal());
    }

    @Test
    @DisplayName("Droits INCONNUS (accessViewpoint absent) → on ne bloque rien : la tentative dira le reste")
    void unknown_access_never_blocks() {
        ObjectNode item = mapper.createObjectNode();
        item.put("webUrl", "https://cagip-my.sharepoint.com/personal/x/reunion.mp4");

        SharePointItemAccess access = SharePointItemAccess.parse(item, null);

        assertFalse(access.hasAccessViewpoint());
        assertTrue(access.downloadable(), "droits inconnus ≠ refus");
        assertTrue(access.downloadRefusal().isEmpty());
    }

    @Test
    @DisplayName("Rien n'est recopié en aveugle : ni adresse signée, ni jeton, ni champ inconnu ne sortent")
    void nothing_is_copied_blindly() {
        // Le corps réel porte une adresse pré-authentifiée (@content.downloadUrl) et un jeton : aucun
        // ne doit franchir la couche. Seuls webUrl (le lien web nu) et les booléens en sortent.
        JsonNode item = TeamsSamples.read("sharepoint-driveitem-cagip.json");
        SharePointItemAccess access = SharePointItemAccess.parse(item,
                TeamsSamples.read("sharepoint-driveitem-labelpolicies-cagip.json"));

        String rendered = access.toString();
        assertFalse(rendered.contains("SECRET-SIGNED-TOKEN"), rendered);
        assertFalse(rendered.contains("tempauth"), rendered);
        assertFalse(rendered.contains("downloadUrl"), rendered);
        assertFalse(access.webUrl().contains("tempauth"),
                "webUrl est le lien web nu, jamais l'adresse signée");
    }

    @Test
    @DisplayName("Un corps null ne lève jamais : droits inconnus, aucun refus")
    void never_throws_on_null() {
        SharePointItemAccess access = SharePointItemAccess.parse(null, null);
        assertFalse(access.hasAccessViewpoint());
        assertFalse(access.hasIrm());
        assertTrue(access.downloadable());
        assertEquals("", access.webUrl());
    }
}
