import { DOCUMENT } from '@angular/common';
import { Injectable, NgZone, inject, signal } from '@angular/core';

/** Clé locale du réglage « Voile discret » (F-259 / SF-259-01) : propre à l'appareil. */
// SF-259-02 : clé v2 — le voile devient actif par défaut ; les réglages d'avant (enregistrés quand il était désactivé
// par défaut) sont ignorés une fois, pour qu'il soit actif partout.
export const VEIL_SETTINGS_KEY = 'cg.veil.v2';

/** Délais d'inactivité proposés, en minutes (mini-spec : 1, 2, 5, 10 ; 2 par défaut). */
export const VEIL_DELAYS_MIN: readonly number[] = [1, 2, 5, 10];

/** Fenêtre du double Échap. */
export const VEIL_DOUBLE_ESCAPE_MS = 500;

/** Titre de l'onglet pendant le voile. */
export const VEIL_TITLE = 'Documentation';

/** Icône d'onglet neutre : une feuille grise (gris DESIGN_SYSTEM), sans marque. */
export const VEIL_FAVICON =
  'data:image/svg+xml,' +
  encodeURIComponent(
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">' +
      '<rect x="6" y="3" width="20" height="26" rx="2" fill="#F5F6FA" stroke="#6B7A8D" stroke-width="2"/>' +
      '<path d="M10 11h12M10 16h12M10 21h8" stroke="#6B7A8D" stroke-width="2" stroke-linecap="round"/>' +
      '</svg>',
  );

export interface VeilSettings {
  enabled: boolean;
  delayMin: number;
}

/** SF-259-02 (décision PO du 2026-11-27) : actif par défaut sur tout appareil. */
export const DEFAULT_VEIL_SETTINGS: VeilSettings = { enabled: true, delayMin: 2 };

/** Lit le réglage stocké ; toute valeur illisible ou hors liste retombe sur le défaut. */
export function readVeilSettings(raw: string | null): VeilSettings {
  if (!raw) {
    return { ...DEFAULT_VEIL_SETTINGS };
  }
  try {
    const parsed = JSON.parse(raw) as Partial<VeilSettings>;
    return {
      enabled: parsed.enabled !== false,
      delayMin: VEIL_DELAYS_MIN.includes(Number(parsed.delayMin)) ? Number(parsed.delayMin) : DEFAULT_VEIL_SETTINGS.delayMin,
    };
  } catch {
    return { ...DEFAULT_VEIL_SETTINGS };
  }
}

/** Ce qui compte comme activité (relance le compte d'inactivité). */
const ACTIVITY_EVENTS = ['mousemove', 'mousedown', 'keydown', 'wheel', 'touchstart', 'pointerdown'] as const;

/**
 * **Le voile discret** (F-259 / SF-259-01). Un calque d'aspect banal se pose par-dessus l'écran — double Échap
 * (< 500 ms) ou inactivité — et seul un clic l'enlève. Rien n'est retiré de l'application : le tour d'agent, le flux
 * et Jarvis continuent derrière. Pendant le voile, l'onglet s'appelle « Documentation » avec une icône neutre ;
 * au retrait, le titre et l'icône que la page veut (même changés entre-temps) sont rétablis.
 *
 * <p>Actif seulement là où un écran le monte (coquille et Jarvis), via {@link attach}/{@link detach}, et seulement
 * si le réglage de l'appareil est activé (activé par défaut depuis SF-259-02).</p>
 */
@Injectable({ providedIn: 'root' })
export class DiscreetVeilService {
  private readonly doc = inject(DOCUMENT);
  private readonly zone = inject(NgZone);

  private readonly settingsState = signal<VeilSettings>(this.load());
  private readonly veiledState = signal(false);
  /** Faux si le stockage local est inaccessible (navigation privée, accès refusé). */
  readonly storageAvailable = signal(this.probeStorage());

  readonly settings = this.settingsState.asReadonly();
  readonly veiled = this.veiledState.asReadonly();

  private hosts = 0;
  private lastEscape = -Infinity;
  private lastActivity = Date.now();
  private idleTimer: ReturnType<typeof setTimeout> | null = null;
  private savedTitle: string | null = null;
  private savedIconHref: string | null = null;
  private savedIconType: string | null = null;
  private observer: MutationObserver | null = null;

  private readonly onKeydown = (event: KeyboardEvent): void => this.handleKeydown(event);
  private readonly onActivity = (): void => this.markActivity();
  private readonly onWheelVeiled = (event: Event): void => {
    if (this.veiledState()) {
      event.preventDefault();
    }
  };
  private readonly onVisibility = (): void => this.checkIdleOnReturn();

  /** Un écran couvert (coquille, Jarvis) se branche ; l'écoute ne vit que tant qu'au moins un écran est monté. */
  attach(): void {
    this.hosts += 1;
    if (this.hosts > 1) {
      return;
    }
    const win = this.doc.defaultView;
    this.zone.runOutsideAngular(() => {
      // Phase de capture : le voile voit les touches avant le terminal (et peut les retenir).
      win?.addEventListener('keydown', this.onKeydown, true);
      for (const type of ACTIVITY_EVENTS) {
        win?.addEventListener(type, this.onActivity, { capture: true, passive: true });
      }
      win?.addEventListener('wheel', this.onWheelVeiled, { capture: true, passive: false });
      this.doc.addEventListener('visibilitychange', this.onVisibility);
    });
    this.markActivity();
  }

  /** L'écran couvert disparaît (ex. retour à une page publique) : on retire le voile et toute écoute. */
  detach(): void {
    if (this.hosts === 0) {
      return;
    }
    this.hosts -= 1;
    if (this.hosts > 0) {
      return;
    }
    const win = this.doc.defaultView;
    win?.removeEventListener('keydown', this.onKeydown, true);
    for (const type of ACTIVITY_EVENTS) {
      win?.removeEventListener(type, this.onActivity, true);
    }
    win?.removeEventListener('wheel', this.onWheelVeiled, true);
    this.doc.removeEventListener('visibilitychange', this.onVisibility);
    this.clearIdleTimer();
    this.unveil();
  }

  update(change: Partial<VeilSettings>): void {
    const next = readVeilSettings(JSON.stringify({ ...this.settingsState(), ...change }));
    this.settingsState.set(next);
    try {
      this.doc.defaultView?.localStorage.setItem(VEIL_SETTINGS_KEY, JSON.stringify(next));
      this.storageAvailable.set(true);
    } catch {
      this.storageAvailable.set(false);
    }
    this.markActivity();
  }

  /** Pose le voile (sans effet s'il est déjà posé). */
  veil(): void {
    if (this.veiledState()) {
      return;
    }
    this.clearIdleTimer();
    this.savedTitle = this.doc.title;
    const link = this.iconLink();
    this.savedIconHref = link?.getAttribute('href') ?? null;
    this.savedIconType = link?.getAttribute('type') ?? null;
    this.applyNeutralTab();
    this.watchTab();
    this.zone.run(() => this.veiledState.set(true));
  }

  /** Enlève le voile : rétablit le titre et l'icône que la page veut, relance le compte d'inactivité. */
  unveil(): void {
    if (!this.veiledState()) {
      return;
    }
    this.observer?.disconnect();
    this.observer = null;
    if (this.savedTitle !== null) {
      this.doc.title = this.savedTitle;
    }
    const link = this.iconLink();
    if (link) {
      this.restoreAttr(link, 'href', this.savedIconHref);
      this.restoreAttr(link, 'type', this.savedIconType);
    }
    this.savedTitle = null;
    this.savedIconHref = null;
    this.savedIconType = null;
    this.zone.run(() => this.veiledState.set(false));
    this.markActivity();
  }

  private handleKeydown(event: KeyboardEvent): void {
    if (this.veiledState()) {
      // Rien ne se tape ni ne se déclenche dessous ; Échap compris.
      event.stopImmediatePropagation();
      if (!event.ctrlKey && !event.metaKey) {
        event.preventDefault();
      }
      return;
    }
    if (event.key !== 'Escape' || event.repeat || !this.settingsState().enabled) {
      return;
    }
    const now = Date.now();
    if (now - this.lastEscape < VEIL_DOUBLE_ESCAPE_MS) {
      this.lastEscape = -Infinity;
      // Le second Échap sert au voile : il ne ferme rien d'autre dessous.
      event.stopImmediatePropagation();
      event.preventDefault();
      this.veil();
      return;
    }
    // Premier Échap : il poursuit son chemin (fermer le panneau ouvert).
    this.lastEscape = now;
  }

  private markActivity(): void {
    this.lastActivity = Date.now();
    if (this.veiledState()) {
      return;
    }
    this.armIdleTimer();
  }

  private armIdleTimer(): void {
    this.clearIdleTimer();
    if (this.hosts === 0 || !this.settingsState().enabled) {
      return;
    }
    const delay = this.settingsState().delayMin * 60_000;
    this.zone.runOutsideAngular(() => {
      this.idleTimer = setTimeout(() => this.veil(), delay);
    });
  }

  /** Onglet en arrière-plan, les minuteries ralentissent : au retour, l'échéance passée pose le voile. */
  private checkIdleOnReturn(): void {
    if (this.doc.hidden || this.veiledState() || this.hosts === 0 || !this.settingsState().enabled) {
      return;
    }
    if (Date.now() - this.lastActivity >= this.settingsState().delayMin * 60_000) {
      this.veil();
    }
  }

  private clearIdleTimer(): void {
    if (this.idleTimer !== null) {
      clearTimeout(this.idleTimer);
      this.idleTimer = null;
    }
  }

  private applyNeutralTab(): void {
    if (this.doc.title !== VEIL_TITLE) {
      this.doc.title = VEIL_TITLE;
    }
    const link = this.iconLink();
    if (link && link.getAttribute('href') !== VEIL_FAVICON) {
      link.setAttribute('href', VEIL_FAVICON);
      link.setAttribute('type', 'image/svg+xml');
    }
  }

  /**
   * Pendant le voile, une page peut changer le titre ou l'icône (navigation, signal d'onglet) : on retient ce
   * qu'elle voulait — pour le rétablir au retrait — et on remet l'onglet neutre.
   */
  private watchTab(): void {
    const head = this.doc.head;
    if (!head || typeof MutationObserver === 'undefined') {
      return;
    }
    this.observer = new MutationObserver(() => {
      const title = this.doc.title;
      if (title !== VEIL_TITLE) {
        this.savedTitle = title;
      }
      const link = this.iconLink();
      const href = link?.getAttribute('href') ?? null;
      if (link && href !== VEIL_FAVICON) {
        this.savedIconHref = href;
        this.savedIconType = link.getAttribute('type');
      }
      this.applyNeutralTab();
    });
    this.observer.observe(head, { subtree: true, childList: true, characterData: true, attributes: true,
      attributeFilter: ['href', 'type'] });
  }

  private restoreAttr(el: Element, name: string, value: string | null): void {
    if (value === null) {
      el.removeAttribute(name);
    } else {
      el.setAttribute(name, value);
    }
  }

  private iconLink(): HTMLLinkElement | null {
    return this.doc.querySelector('link[rel="icon"]');
  }

  private load(): VeilSettings {
    try {
      return readVeilSettings(this.doc.defaultView?.localStorage.getItem(VEIL_SETTINGS_KEY) ?? null);
    } catch {
      // Stockage inaccessible : le réglage ne pourrait pas être désactivé, donc le voile reste éteint.
      return { ...DEFAULT_VEIL_SETTINGS, enabled: false };
    }
  }

  private probeStorage(): boolean {
    try {
      const storage = this.doc.defaultView?.localStorage;
      if (!storage) {
        return false;
      }
      storage.getItem(VEIL_SETTINGS_KEY);
      return true;
    } catch {
      return false;
    }
  }
}
