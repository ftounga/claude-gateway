import { HttpClient } from '@angular/common/http';
import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Router } from '@angular/router';

/** Une notification du centre (F-185 / SF-185-04). */
export interface NotificationItem {
  id: string;
  event: string;
  title: string;
  subject: string | null;
  workspaceId: string | null;
  createdAt: string;
  read: boolean;
}

export interface NotificationView {
  unread: number;
  items: NotificationItem[];
}

/** Cadence du relevé, tant que l'onglet est visible. */
export const NOTIFICATIONS_POLL_MS = 30_000;

/** Durée du bandeau « Réponse arrivée — <sujet> ». */
export const NOTIFICATION_BANNER_MS = 8_000;

/** « titre — sujet », ou le titre seul. */
export function notificationLabel(item: Pick<NotificationItem, 'title' | 'subject'>): string {
  return item.subject ? `${item.title} — ${item.subject}` : item.title;
}

/** « à l'instant », « il y a 5 min », « il y a 3 h », « hier », « il y a 4 j ». */
export function relativeTime(iso: string, now = Date.now()): string {
  const at = Date.parse(iso);
  if (Number.isNaN(at)) {
    return '';
  }
  const minutes = Math.floor(Math.max(0, now - at) / 60_000);
  if (minutes < 1) {
    return "à l'instant";
  }
  if (minutes < 60) {
    return `il y a ${minutes} min`;
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    return `il y a ${hours} h`;
  }
  const days = Math.floor(hours / 24);
  return days === 1 ? 'hier' : `il y a ${days} j`;
}

/** Le terminal affiché, lu dans l'URL (`/atelier/{id}`), ou `null`. */
export function displayedTerminal(url: string): string | null {
  const match = /^\/atelier\/([^/?#]+)/.exec(url);
  return match ? match[1] : null;
}

/**
 * **Le centre de notifications, vu de l'écran** (F-185 / SF-185-04) : la cloche lit ici les non-lus
 * et les dernières notifications. Un relevé toutes les trente secondes tant que l'onglet est
 * visible, et tout de suite quand il le redevient — pas de canal persistant de plus (F-70).
 *
 * <p><b>D6</b> : une nouveauté d'un <b>autre</b> terminal que celui affiché se dit dans un bandeau
 * « Réponse arrivée — <sujet> » avec [Ouvrir]. Jamais au premier relevé : l'historique n'est pas
 * une nouveauté.</p>
 */
@Injectable({ providedIn: 'root' })
export class NotificationCenterService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);

  private readonly view = signal<NotificationView>({ unread: 0, items: [] });
  readonly unread = computed(() => this.view().unread);
  readonly items = computed(() => this.view().items);

  /** Identifiants déjà vus ; `null` tant que le premier relevé n'a pas répondu. */
  private known: Set<string> | null = null;
  private timer: ReturnType<typeof setInterval> | null = null;
  private readonly onVisible = () => {
    if (document.visibilityState === 'visible') {
      this.refresh();
    }
  };

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stop());
  }

  /** Démarre le relevé (coquille authentifiée). Idempotent. */
  start(): void {
    if (this.timer !== null) {
      return;
    }
    this.refresh();
    this.timer = setInterval(() => {
      if (typeof document === 'undefined' || document.visibilityState === 'visible') {
        this.refresh();
      }
    }, NOTIFICATIONS_POLL_MS);
    if (typeof document !== 'undefined') {
      document.addEventListener('visibilitychange', this.onVisible);
    }
  }

  stop(): void {
    if (this.timer !== null) {
      clearInterval(this.timer);
      this.timer = null;
    }
    if (typeof document !== 'undefined') {
      document.removeEventListener('visibilitychange', this.onVisible);
    }
  }

  refresh(): void {
    this.http.get<NotificationView>('/api/notifications').subscribe({
      next: (view) => this.apply(view),
      // Réseau, 500 : la cloche garde son dernier état, sans rien dire.
      error: () => undefined,
    });
  }

  /** Marque lue et ouvre le terminal. */
  open(item: NotificationItem): void {
    if (!item.read) {
      this.markRead(item);
    }
    if (item.workspaceId) {
      void this.router.navigateByUrl(`/atelier/${item.workspaceId}`);
    }
  }

  markRead(item: NotificationItem): void {
    this.view.update((v) => ({
      unread: Math.max(0, v.unread - (item.read ? 0 : 1)),
      items: v.items.map((i) => (i.id === item.id ? { ...i, read: true } : i)),
    }));
    this.http.post(`/api/notifications/${item.id}/read`, null).subscribe({ error: () => undefined });
  }

  markAllRead(): void {
    this.view.update((v) => ({ unread: 0, items: v.items.map((i) => ({ ...i, read: true })) }));
    this.http.post('/api/notifications/read-all', null).subscribe({ error: () => undefined });
  }

  private apply(view: NotificationView): void {
    const known = this.known;
    this.known = new Set(view.items.map((i) => i.id));
    this.view.set(view);
    if (known === null) {
      return;
    }
    const here = displayedTerminal(this.router.url);
    // Les items arrivent du plus récent au plus ancien : le premier nouveau est le plus récent.
    const fresh = view.items.find((i) => !known.has(i.id) && !i.read && i.workspaceId !== here);
    if (fresh) {
      this.banner(fresh);
    }
  }

  private banner(item: NotificationItem): void {
    const ref = this.snackBar.open(notificationLabel(item), item.workspaceId ? 'Ouvrir' : 'Fermer', {
      duration: NOTIFICATION_BANNER_MS,
    });
    if (item.workspaceId) {
      ref.onAction().subscribe(() => this.open(item));
    }
  }
}
