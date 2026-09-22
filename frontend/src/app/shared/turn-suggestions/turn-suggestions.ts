import { AtelierPlanStep } from '../../core/models/atelier.models';

/**
 * Ce qu'on sait du dernier tour, tel que l'écran le porte réellement (F-144 / SF-144-01).
 *
 * <p><b>Volontairement pas `AtelierTurnReport`</b> : ce type-là décrit le relevé côté serveur, et le
 * fil de l'écran n'en garde qu'une partie — l'interruption, le plafond et les diffs sont sur le
 * message, le plan ne vit que dans l'état du tour en cours. Cette forme dit exactement ce qui est
 * disponible, plutôt que de promettre un champ qui arrivera toujours vide.</p>
 */
export interface TurnOutcome {
  interrupted?: boolean;
  budgetReached?: boolean;
  /** Fichiers modifiés — on ne lit que leur nombre. */
  diffs?: unknown[];
  /** Le plan, quand il est encore connu (tour en cours ou tout juste fini). */
  plan?: AtelierPlanStep[];
}

/** Une suite proposée à l'utilisateur (F-144 / SF-144-01). */
export interface TurnSuggestion {
  /** Ce qui s'affiche sur la puce. */
  label: string;
  /** Ce qui remplit le champ au clic — jamais envoyé sans un geste. */
  text: string;
}

/** Trois au plus : au-delà, ce n'est plus une suggestion, c'est un menu. */
export const MAX_SUGGESTIONS = 3;

/** Un titre d'étape très long est coupé, jamais rejeté. */
const MAX_STEP_CHARS = 60;

/**
 * **La suite logique du travail en cours** (F-144 / SF-144-01).
 *
 * <p><b>Fonction pure, et sans aucun appel au modèle.</b> Une suggestion produite par un appel
 * coûterait un tour de plus à chaque réponse — ce que F-134 vient de faire baisser d'un facteur 16 —
 * et ajouterait une attente avant de pouvoir taper. Tout ce qu'il faut est déjà dans le relevé que
 * l'écran affiche : le plan et l'état de ses étapes, l'interruption, le plafond, les fichiers
 * modifiés.</p>
 *
 * <p><b>L'ordre est celui de l'évidence</b>, pas celui du code : ce qui reste à faire d'abord, ce
 * qui s'est arrêté ensuite, ce qui mérite vérification en dernier.</p>
 */
export function suggestionsFor(report: TurnOutcome | null | undefined): TurnSuggestion[] {
  if (!report) {
    return [];
  }
  const suggestions: TurnSuggestion[] = [];

  // 1. Une étape de plan restée ouverte : c'est la suite la plus littérale qui existe.
  const next = nextOpenStep(report.plan);
  if (next) {
    const title = trim(next.title);
    suggestions.push({
      label: `Continuer : ${title}`,
      text: `Continue : ${title}`,
    });
  }

  // 2. Le tour s'est arrêté avant d'avoir fini — sur un geste, ou sur le plafond.
  if (report.interrupted) {
    suggestions.push({
      label: 'Reprends là où tu t\'es arrêté',
      text: 'Reprends là où tu t\'es arrêté.',
    });
  } else if (report.budgetReached) {
    // `else` : deux façons de s'arrêter, une seule suggestion — les deux puces diraient la même
    // chose à l'utilisateur, qui n'a qu'un geste à faire.
    suggestions.push({
      label: 'Reprends : le plafond du tour a été atteint',
      text: 'Reprends : le plafond du tour a été atteint.',
    });
  }

  // 3. Des fichiers ont changé : sur une machine de client, ce qui vient après une modification est
  // toujours de constater son effet.
  if ((report.diffs?.length ?? 0) > 0) {
    suggestions.push({
      label: 'Vérifie l\'état après ces modifications',
      text: 'Vérifie l\'état après ces modifications, et dis-moi ce que tu constates.',
    });
  }

  return dedupe(suggestions).slice(0, MAX_SUGGESTIONS);
}

/** La première étape qui n'est pas faite — active d'abord, puis en attente. */
function nextOpenStep(plan: AtelierPlanStep[] | undefined): AtelierPlanStep | null {
  if (!plan || plan.length === 0) {
    return null;
  }
  const active = plan.find((step) => normalize(step.status) === 'active');
  if (active) {
    return active;
  }
  return plan.find((step) => normalize(step.status) !== 'done') ?? null;
}

function normalize(status: string | null | undefined): string {
  return (status ?? '').trim().toLowerCase();
}

function trim(title: string): string {
  const cleaned = (title ?? '').trim();
  return cleaned.length <= MAX_STEP_CHARS ? cleaned : `${cleaned.slice(0, MAX_STEP_CHARS)}…`;
}

function dedupe(suggestions: TurnSuggestion[]): TurnSuggestion[] {
  const seen = new Set<string>();
  return suggestions.filter((suggestion) => {
    if (seen.has(suggestion.text)) {
      return false;
    }
    seen.add(suggestion.text);
    return true;
  });
}
