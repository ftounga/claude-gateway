package fr.claudegateway.auth;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * Condition d'activation d'OAuth2 Google : vraie uniquement si {@code app.oauth2.google.client-id}
 * a une valeur <b>non vide</b>.
 *
 * <p>On ne peut pas utiliser {@code @ConditionalOnProperty} : la config déclare
 * {@code client-id: ${GOOGLE_CLIENT_ID:}} qui, en l'absence de la variable d'environnement, se
 * résout en <b>chaîne vide</b> — considérée « présente » par {@code @ConditionalOnProperty}.
 * Ici on exige un texte réel, garantissant qu'OAuth reste dormant sans identifiants.</p>
 */
public class GoogleOAuthConfiguredCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String clientId = context.getEnvironment().getProperty("app.oauth2.google.client-id");
        return StringUtils.hasText(clientId);
    }
}
