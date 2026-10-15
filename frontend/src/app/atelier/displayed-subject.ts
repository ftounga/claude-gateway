/**
 * F-198 / SF-198-01 — **Un flux n'écrit que dans le sujet qui l'a lancé.**
 *
 * L'écran de l'Atelier est réutilisé d'un sujet à l'autre (`/atelier/A` → `/atelier/B`) : un flux
 * d'émission lancé sur A continue de livrer ses événements après le passage à B. Le tour de A doit
 * continuer côté serveur — on n'abandonne donc pas sa requête —, mais plus rien de ce qu'il livre ne
 * doit toucher l'écran de B.
 *
 * Rend une copie de `handlers` dont chaque rappel devient muet dès que `live()` est faux. Un rappel
 * muet rend `undefined` : pour `acceptSeq`, c'est un refus — l'événement est écarté avant tout effet.
 */
export function guardHandlers<T extends object>(handlers: T, live: () => boolean): T {
  const guarded: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(handlers)) {
    guarded[key] = typeof value === 'function'
      ? (...args: unknown[]) => (live() ? (value as (...a: unknown[]) => unknown)(...args) : undefined)
      : value;
  }
  return guarded as T;
}
