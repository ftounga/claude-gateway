import { Injectable } from '@angular/core';

import { ProviderMode } from '../models/billing.models';

const ONBOARDING_STORAGE_KEY = 'cg_onboarding';

/** État d'onboarding mémorisé côté client. */
interface OnboardingState {
  completed: boolean;
  mode: ProviderMode | null;
}

/**
 * Suivi de l'onboarding (F-12) côté client.
 *
 * Le mode fournisseur choisi n'est pas persisté côté serveur : aucun endpoint V1 ne l'expose
 * (PROJECT.md §11.3 le prévoit, mais F-01/F-09 ne l'implémentent pas ; la gestion de clé BYOK relève
 * de F-03, non livrée). L'onboarding est une couche de guidage/routage ; la préférence est donc
 * conservée en `localStorage`. Décision réversible : lorsqu'un endpoint exposera le mode, ce service
 * y déléguera sans impacter les composants.
 */
@Injectable({ providedIn: 'root' })
export class OnboardingService {
  /** Vrai si l'utilisateur a déjà terminé (ou explicitement passé) l'onboarding. */
  isCompleted(): boolean {
    return this.read().completed;
  }

  /** Mode fournisseur choisi lors de l'onboarding, ou `null` si non encore choisi. */
  providerMode(): ProviderMode | null {
    return this.read().mode;
  }

  /** Marque l'onboarding comme terminé et mémorise le mode fournisseur retenu. */
  complete(mode: ProviderMode): void {
    this.write({ completed: true, mode });
  }

  /**
   * Destination après authentification : `/onboarding` tant que le parcours n'est pas terminé,
   * sinon `/profile` (comportement historique de F-01).
   */
  postLoginPath(): string {
    return this.isCompleted() ? '/profile' : '/onboarding';
  }

  private read(): OnboardingState {
    try {
      const raw = localStorage.getItem(ONBOARDING_STORAGE_KEY);
      if (!raw) {
        return { completed: false, mode: null };
      }
      const parsed = JSON.parse(raw) as Partial<OnboardingState>;
      return {
        completed: parsed.completed === true,
        mode: parsed.mode === 'HOSTED' || parsed.mode === 'BYOK' ? parsed.mode : null,
      };
    } catch {
      return { completed: false, mode: null };
    }
  }

  private write(state: OnboardingState): void {
    localStorage.setItem(ONBOARDING_STORAGE_KEY, JSON.stringify(state));
  }
}
