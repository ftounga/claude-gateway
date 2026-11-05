package fr.claudegateway.jarvis.state;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import fr.claudegateway.jarvis.portrait.PortraitSeal;

/**
 * <b>Le scellé des valeurs d'état</b> (F-247 / SF-247-02, D4) : {@link PortraitSeal} (KMS en prod), avec un petit cache
 * <b>en mémoire du pod</b>, borné (LRU), du scellé vers le clair. Il évite un appel KMS à chaque comparaison d'upsert
 * et à chaque lecture de l'outil {@code etat} (porte Z10) ; le clair ne quitte jamais la mémoire et n'est jamais
 * journalisé.
 */
@Component
public class JarvisStateSeal {

    static final int MAX_ENTRIES = 20_000;

    private final PortraitSeal seal;
    private final Map<String, String> opened = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    public JarvisStateSeal(PortraitSeal seal) {
        this.seal = seal;
    }

    public String seal(String plain) {
        String sealed = seal.seal(plain);
        remember(sealed, plain == null ? "" : plain);
        return sealed;
    }

    /** Ouvre un scellé ; {@code null} ⇒ {@code null} ; illisible ⇒ {@code null} (le fait est tu, jamais inventé). */
    public String open(String sealed) {
        if (sealed == null) {
            return null;
        }
        synchronized (opened) {
            String hit = opened.get(sealed);
            if (hit != null) {
                return hit;
            }
        }
        try {
            String plain = seal.open(sealed);
            remember(sealed, plain);
            return plain;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void remember(String sealed, String plain) {
        if (plain == null) {
            return;
        }
        synchronized (opened) {
            opened.put(sealed, plain);
        }
    }
}
