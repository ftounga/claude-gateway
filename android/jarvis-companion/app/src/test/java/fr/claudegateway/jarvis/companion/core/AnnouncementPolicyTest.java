package fr.claudegateway.jarvis.companion.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/** F-232 / SF-232-03 — ce qui se dit, quand, une seule fois. */
public class AnnouncementPolicyTest {

    @Test
    public void neParleQueVerrouilleOuEcranEteint() {
        assertTrue(AnnouncementPolicy.shouldSpeak(true, true, false));
        assertTrue(AnnouncementPolicy.shouldSpeak(false, false, false));
        assertFalse("téléphone en main : l'écran fait foi", AnnouncementPolicy.shouldSpeak(false, true, false));
        assertFalse("Jarvis ouvert par le compagnon", AnnouncementPolicy.shouldSpeak(true, false, true));
    }

    @Test
    public void uneAnnonceNeSeDitQuUneFois() {
        AnnouncementPolicy policy = new AnnouncementPolicy();
        List<AnnouncementPolicy.Item> first = policy.fresh(Arrays.asList(
                new AnnouncementPolicy.Item("a", "C'est fait."), new AnnouncementPolicy.Item("b", "Veille : x")));
        assertEquals(2, first.size());
        List<AnnouncementPolicy.Item> again = policy.fresh(Arrays.asList(
                new AnnouncementPolicy.Item("b", "Veille : x"), new AnnouncementPolicy.Item("c", "Prêt.")));
        assertEquals(1, again.size());
        assertEquals("c", again.get(0).id);
    }

    @Test
    public void ignoreLesAnnoncesVidesEtBorneLaMemoire() {
        AnnouncementPolicy policy = new AnnouncementPolicy();
        assertTrue(policy.fresh(Arrays.asList(new AnnouncementPolicy.Item("x", " "),
                new AnnouncementPolicy.Item(null, "t"))).isEmpty());
        List<AnnouncementPolicy.Item> many = new ArrayList<>();
        for (int i = 0; i < AnnouncementPolicy.MEMORY + 1; i++) {
            many.add(new AnnouncementPolicy.Item("id-" + i, "t"));
        }
        assertEquals(AnnouncementPolicy.MEMORY + 1, policy.fresh(many).size());
        // La plus ancienne est sortie de la mémoire ; la plus récente y est encore.
        assertEquals(1, policy.fresh(Collections.singletonList(new AnnouncementPolicy.Item("id-0", "t"))).size());
        assertTrue(policy.fresh(Collections.singletonList(
                new AnnouncementPolicy.Item("id-" + AnnouncementPolicy.MEMORY, "t"))).isEmpty());
    }

    @Test
    public void espaceLaReleveApresUneErreur() {
        AnnouncementPolicy policy = new AnnouncementPolicy();
        assertEquals(20_000L, policy.nextDelay(false));
        assertEquals(40_000L, policy.nextDelay(false));
        assertEquals(80_000L, policy.nextDelay(false));
        assertEquals(120_000L, policy.nextDelay(false));
        assertEquals(120_000L, policy.nextDelay(false));
        assertEquals(20_000L, policy.nextDelay(true));
    }
}
