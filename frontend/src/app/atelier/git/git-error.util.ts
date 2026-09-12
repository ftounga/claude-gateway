import { HttpErrorResponse } from '@angular/common/http';

import { httpErrorMessage } from '../../shared/http-error.util';

/**
 * Message d'erreur d'ouverture de dépôt (F-31 / SF-31-02).
 *
 * <p>Chaque cause appelle une <b>action différente</b> — enregistrer un jeton, corriger l'URL,
 * réessayer plus tard — et les confondre laisserait l'utilisateur sans geste à faire.</p>
 *
 * <p>Fonction partagée depuis F-72 / SF-72-04 : le geste « ouvrir un dépôt GitHub » a quitté la
 * barre latérale pour la carte « Hébergé », et deux copies de ces messages divergeraient au premier
 * code d'erreur ajouté côté gateway.</p>
 */
export function gitErrorMessage(err: unknown): string {
  const code =
    err instanceof HttpErrorResponse ? (err.error as { error?: string } | null)?.error : undefined;
  switch (code) {
    case 'git_token_missing':
      return 'Aucun jeton GitHub enregistré : ajoutez-en un dans vos réglages.';
    case 'invalid_git_token':
      return 'GitHub a refusé votre jeton. Remplacez-le dans vos réglages.';
    case 'invalid_git_repository':
      return 'Dépôt introuvable, ou hors de portée de votre jeton GitHub.';
    case 'invalid_git_branch':
      return 'Nom de branche invalide.';
    case 'github_unavailable':
      return 'GitHub est momentanément indisponible. Réessayez dans un instant.';
    default:
      return httpErrorMessage(err, "L'ouverture du dépôt a échoué.");
  }
}
