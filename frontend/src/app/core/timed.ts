/**
 * F-233 / SF-233-16 — **une promesse bornée** (audit du 2026-11-07) : rejetée `Error('timeout')` après `ms` ; si la
 * promesse aboutit trop tard, `onLate` reçoit sa valeur (ex. couper les pistes d'un micro ouvert après coup).
 */
export function timed<T>(promise: Promise<T>, ms: number, onLate?: (value: T) => void): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    let over = false;
    const timer = setTimeout(() => {
      over = true;
      reject(new Error('timeout'));
    }, ms);
    promise.then(
      (value) => {
        if (over) {
          try {
            onLate?.(value);
          } catch {
            // rien à rendre
          }
          return;
        }
        over = true;
        clearTimeout(timer);
        resolve(value);
      },
      (error) => {
        if (over) {
          return;
        }
        over = true;
        clearTimeout(timer);
        reject(error);
      },
    );
  });
}

/** Coupe toutes les pistes d'un flux (micro). */
export function stopTracks(stream: MediaStream | null | undefined): void {
  try {
    stream?.getTracks().forEach((track) => track.stop());
  } catch {
    // déjà coupé
  }
}

/** Délai d'ouverture du micro : au-delà, l'ouverture est tenue pour échouée (jamais d'attente sans fin). */
export const MIC_OPEN_TIMEOUT_MS = 6_000;
