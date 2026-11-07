import { Injectable, inject } from '@angular/core';

import { AuthService } from '../core/services/auth.service';

/**
 * **Le journal de l'écoute** (F-233 / SF-233-15) — chaque transition et chaque erreur de la boucle d'écoute mains libres
 * de l'écran Jarvis, rangée sur le serveur (`POST /api/jarvis/listen-trace`) pour savoir **où** la boucle s'arrête.
 *
 * **Aucun contenu** : des noms d'événements (`[a-z0-9_]`), des états, des durées, des noms d'erreurs, des longueurs —
 * jamais un texte dit ou entendu. Best-effort : un envoi raté est perdu, l'écran continue. Aucune minuterie : l'envoi
 * se fait au fil des événements (lot de 20, ou 4 s écoulées), tout de suite sur un événement urgent, et à la sortie.
 */
export interface TraceEvent {
  seq: number;
  /** ms depuis l'ouverture de la page. */
  t: number;
  ev: string;
  st: string;
  md: string;
  d?: string;
  /** Répétitions d'un même événement consécutif (≥ 2 seulement). */
  n?: number;
}

export interface TraceBatch {
  pageId: string;
  version: string | null;
  companion: boolean;
  events: TraceEvent[];
}

/** Au plus autant d'événements par ouverture de page. */
export const TRACE_MAX_EVENTS = 1_500;
/** Un lot part à cette taille… */
export const TRACE_BATCH_SIZE = 20;
/** … ou quand ce temps est écoulé depuis le dernier envoi (au prochain événement). */
export const TRACE_FLUSH_MS = 4_000;
/** Les événements qui partent tout de suite (erreurs, arrêts de la boucle, sortie). */
const URGENT = /(_fail|_error|_timeout|_stall|_orphan|error|pause|rest|idle_end|pagehide|hidden|reload|watchdog)/;

/** L'envoi d'un lot — isolé pour être remplacé en test (aucun réseau). */
@Injectable({ providedIn: 'root' })
export class JarvisTraceSink {
  private readonly auth = inject(AuthService);

  send(batch: TraceBatch): void {
    try {
      const token = this.auth.token();
      void fetch('/api/jarvis/listen-trace', {
        method: 'POST',
        keepalive: true,
        headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
        body: JSON.stringify(batch),
      }).catch(() => undefined);
    } catch {
      // Best-effort : le journal ne gêne jamais l'écran.
    }
  }
}

/** L'empreinte de la page servie : le hash du `main-*.js` (la version déployée), sinon `null`. */
export function pageVersion(doc: Pick<Document, 'querySelectorAll'> | null =
  typeof document === 'undefined' ? null : document): string | null {
  try {
    const scripts = Array.from(doc?.querySelectorAll<HTMLScriptElement>('script[src]') ?? []);
    for (const script of scripts) {
      const match = /main-([A-Za-z0-9]{6,16})\.js/.exec(script.getAttribute('src') ?? '');
      if (match) {
        return match[1];
      }
    }
  } catch {
    // illisible : sans version
  }
  return null;
}

/** Un détail court et technique : jamais de texte libre (liste blanche, 120 caractères). */
export function traceDetail(value: unknown): string | undefined {
  if (value === null || value === undefined) {
    return undefined;
  }
  const text = String(value).replace(/[^A-Za-z0-9 _.:/>=+,-]/g, '').trim().slice(0, 120);
  return text || undefined;
}

/** Le nom d'une erreur (jamais son message, qui peut porter du contenu). */
export function errorName(error: unknown): string {
  if (typeof error === 'string') {
    return error.slice(0, 40);
  }
  const name = (error as { name?: unknown } | null)?.name;
  return typeof name === 'string' && name ? name.slice(0, 40) : 'inconnue';
}

@Injectable({ providedIn: 'root' })
export class JarvisListenTrace {
  private readonly sink = inject(JarvisTraceSink);

  private pageId = randomId();
  private version: string | null = null;
  private companion = false;
  private started = 0;
  private seq = 0;
  private total = 0;
  private buffer: TraceEvent[] = [];
  private lastFlush = 0;
  private state: () => string = () => '';
  private mode: () => string = () => '';
  private detach: (() => void) | null = null;

  /** Ouvre le journal de cette page ; `state`/`mode` sont relus à chaque événement. */
  start(options: { companion: boolean; state: () => string; mode: () => string; version?: string | null }): void {
    this.pageId = randomId();
    this.version = options.version === undefined ? pageVersion() : options.version;
    this.companion = options.companion;
    this.state = options.state;
    this.mode = options.mode;
    this.started = now();
    this.lastFlush = this.started;
    this.seq = 0;
    this.total = 0;
    this.buffer = [];
    this.listen();
  }

  /** Note un événement (nom `[a-z0-9_]`, détail technique court). Ne lève jamais. */
  mark(ev: string, detail?: unknown): void {
    try {
      if (!/^[a-z0-9_]{1,40}$/.test(ev) || this.total >= TRACE_MAX_EVENTS) {
        return;
      }
      const d = traceDetail(detail);
      const st = safe(this.state);
      const md = safe(this.mode);
      const last = this.buffer[this.buffer.length - 1];
      if (last && last.ev === ev && last.d === d && last.st === st && last.md === md) {
        last.n = (last.n ?? 1) + 1;
      } else {
        this.total++;
        this.buffer.push({ seq: ++this.seq, t: Math.max(0, Math.round(now() - this.started)), ev, st, md,
          ...(d ? { d } : {}) });
      }
      if (URGENT.test(ev) || this.buffer.length >= TRACE_BATCH_SIZE || now() - this.lastFlush >= TRACE_FLUSH_MS) {
        this.flush();
      }
    } catch {
      // Le journal ne gêne jamais l'écran.
    }
  }

  /** Envoie ce qui attend. */
  flush(): void {
    if (this.buffer.length === 0) {
      return;
    }
    const events = this.buffer;
    this.buffer = [];
    this.lastFlush = now();
    this.sink.send({ pageId: this.pageId, version: this.version, companion: this.companion, events });
  }

  /** Ferme le journal (sortie de l'écran) : dernier envoi, écouteurs retirés. */
  stop(): void {
    this.mark('screen_exit');
    this.flush();
    this.detach?.();
    this.detach = null;
  }

  /** La page devient invisible, est quittée ou plante : ces moments-là arrêtent la boucle sans rien dire. */
  private listen(): void {
    this.detach?.();
    if (typeof window === 'undefined' || typeof document === 'undefined') {
      return;
    }
    const onVisibility = () => this.mark(document.visibilityState === 'hidden' ? 'page_hidden' : 'page_visible');
    const onPageHide = () => this.mark('pagehide');
    const onFreeze = () => this.mark('page_freeze');
    const onResume = () => this.mark('page_resume');
    const onError = (event: ErrorEvent) => this.mark('js_error', errorName(event.error));
    const onRejection = (event: PromiseRejectionEvent) => this.mark('js_rejection', errorName(event.reason));
    document.addEventListener('visibilitychange', onVisibility);
    document.addEventListener('freeze', onFreeze);
    document.addEventListener('resume', onResume);
    window.addEventListener('pagehide', onPageHide);
    window.addEventListener('error', onError);
    window.addEventListener('unhandledrejection', onRejection);
    this.detach = () => {
      document.removeEventListener('visibilitychange', onVisibility);
      document.removeEventListener('freeze', onFreeze);
      document.removeEventListener('resume', onResume);
      window.removeEventListener('pagehide', onPageHide);
      window.removeEventListener('error', onError);
      window.removeEventListener('unhandledrejection', onRejection);
    };
  }
}

function safe(read: () => string): string {
  try {
    const value = read();
    return /^[a-z_]{0,16}$/.test(value) ? value : '';
  } catch {
    return '';
  }
}

function now(): number {
  return typeof performance !== 'undefined' ? performance.now() : Date.now();
}

function randomId(): string {
  try {
    return crypto.randomUUID();
  } catch {
    return `p${Date.now().toString(36)}${Math.random().toString(36).slice(2, 10)}`;
  }
}
