package fr.claudegateway.jarvis.mail;

import java.util.UUID;

/** Prévient le tri (SF-242-03) qu'une relève a apporté du neuf pour ce compte ; asynchrone, jamais bloquant. */
public interface JarvisMailTriageTrigger {

    void triageSoon(UUID userId);
}
