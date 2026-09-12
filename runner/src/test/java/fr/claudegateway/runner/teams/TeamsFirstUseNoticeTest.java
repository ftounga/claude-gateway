package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-87 / SF-87-02 — décision <b>D3</b> : ce dont la liaison a besoin se dit au premier usage, et le
 * téléchargement, s'il y en a un, <b>se voit</b>.
 */
class TeamsFirstUseNoticeTest {

    private final List<String> said = new ArrayList<>();

    @Test
    @DisplayName("Annoncé une fois, et une seule : une annonce répétée cesse d'être lue")
    void announced_exactly_once() {
        TeamsFirstUseNotice notice = new TeamsFirstUseNotice();

        assertTrue(notice.announceOnce(said::add));
        assertFalse(notice.announceOnce(said::add));
        assertFalse(notice.announceOnce(said::add));
        assertEquals(1, said.size());
    }

    @Test
    @DisplayName("Pour la liaison, rien n'est à télécharger — et c'est dit")
    void nothing_to_download_for_the_link() {
        TeamsFirstUseNotice notice = new TeamsFirstUseNotice();
        notice.announceOnce(said::add);

        assertTrue(notice.everythingAvailable());
        assertTrue(said.get(0).contains("pilotage du navigateur"), said.get(0));
        assertTrue(said.get(0).contains("déjà disponible"), said.get(0));
        assertTrue(said.get(0).contains("Rien à télécharger"), said.get(0));
    }

    @Test
    @DisplayName("Un besoin absent est annoncé comme À TÉLÉCHARGER, et l'attente est nommée")
    void a_missing_requirement_is_announced_loudly() {
        TeamsFirstUseNotice notice = new TeamsFirstUseNotice(List.of(
                new TeamsFirstUseNotice.Requirement("pilotage du navigateur", true, "natif"),
                new TeamsFirstUseNotice.Requirement("ffmpeg", false, "environ 80 Mo")));

        notice.announceOnce(said::add);

        assertFalse(notice.everythingAvailable());
        assertTrue(said.get(0).contains("À TÉLÉCHARGER"), said.get(0));
        assertTrue(said.get(0).contains("80 Mo"), said.get(0));
        assertTrue(said.get(0).contains("une minute d'attente, pas une panne"), said.get(0));
    }

    @Test
    @DisplayName("L'annonce dit ce qui reste sur la machine")
    void says_what_stays_on_the_machine() {
        assertTrue(new TeamsFirstUseNotice().text().contains("Ils ne remontent jamais"));
    }
}
