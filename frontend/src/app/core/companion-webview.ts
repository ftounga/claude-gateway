/**
 * **La WebView du compagnon Android sans cache de page** (F-233 / SF-233-16, audit du 2026-11-07).
 *
 * Le compagnon charge `/jarvis` dans une WebView qui expose `window.JarvisCompanion`. Avec le service worker, la
 * première ouverture après un déploiement exécutait **l'ancienne version** (servie du cache), puis la mise à jour
 * rechargeait la page entre deux tours de la conversation. Dans la WebView, le service worker n'apporte rien (ni hors
 * ligne, ni Web Push) : il n'y est **pas enregistré**, et celui qui y reste d'avant est **désinscrit** ; si la page en
 * cours a été servie par lui, elle est rechargée **une fois** (garde de session contre toute boucle) et vient alors du
 * réseau — la dernière version déployée. Hors compagnon : rien ne change.
 */
export const SW_DROPPED_KEY = 'cg.companion.swDropped';

/** Vrai dans la WebView du compagnon Android (le pont natif est injecté avant le premier script). */
export function inCompanionWebView(win: unknown = typeof window === 'undefined' ? undefined : window): boolean {
  return !!(win as { JarvisCompanion?: unknown } | undefined)?.JarvisCompanion;
}

export type SwDropOutcome = 'none' | 'dropped' | 'reloading';

/** Désinscrit le service worker dans le compagnon ; `reloading` : la page se recharge, ne rien démarrer. */
export async function dropServiceWorkerInCompanion(
  win: unknown = typeof window === 'undefined' ? undefined : window,
  nav: { serviceWorker?: Pick<ServiceWorkerContainer, 'getRegistrations' | 'controller'> } | undefined =
    typeof navigator === 'undefined' ? undefined : navigator,
  storage: Pick<Storage, 'getItem' | 'setItem'> | null = safeSession(),
  reload: () => void = () => location.reload(),
): Promise<SwDropOutcome> {
  const container = nav?.serviceWorker;
  if (!inCompanionWebView(win) || !container) {
    return 'none';
  }
  try {
    const registrations = await container.getRegistrations();
    await Promise.all(registrations.map((r) => r.unregister().catch(() => false)));
    if (container.controller && readFlag(storage) !== '1') {
      writeFlag(storage);
      reload();
      return 'reloading';
    }
    return registrations.length > 0 ? 'dropped' : 'none';
  } catch {
    // Service worker inaccessible : la page continue telle quelle.
    return 'none';
  }
}

function readFlag(storage: Pick<Storage, 'getItem'> | null): string | null {
  try {
    return storage?.getItem(SW_DROPPED_KEY) ?? null;
  } catch {
    return null;
  }
}

function writeFlag(storage: Pick<Storage, 'setItem'> | null): void {
  try {
    storage?.setItem(SW_DROPPED_KEY, '1');
  } catch {
    // sans stockage : au pire un rechargement par ouverture
  }
}

function safeSession(): Storage | null {
  try {
    return typeof sessionStorage === 'undefined' ? null : sessionStorage;
  } catch {
    return null;
  }
}
