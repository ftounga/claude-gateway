import { Component, computed, inject, input, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';
import { firstValueFrom } from 'rxjs';

import { AtelierProofBlock, AtelierProofCapture, AtelierProofCommand, AtelierTerminalBlock } from '../../core/models/atelier.models';
import { CopyBlock } from '../../shared/copy-block.model';
import { clipboardHtml } from '../../chat/copy-block/copy-block.component';
import { splitHeaders } from '../../shared/message-segments';

/** Le bloc de transcription de captures reçues au fil de l'eau (F-212) : rangé comme une carte. */
export function proofBlock(toolUseId: string, proof: AtelierProofBlock): AtelierTerminalBlock {
  return {
    tool: 'capturer_preuves',
    toolUseId,
    threadId: null,
    output: '',
    hasOutput: false,
    error: false,
    expanded: false,
    proof,
  };
}

/** URL de l'image d'une capture, servie au propriétaire du terminal. */
export function proofImageUrl(workspaceId: string | null, captureId: string): string {
  return `/api/workspaces/${workspaceId ?? ''}/proofs/${encodeURIComponent(captureId)}`;
}

/** « code 0 · 1,2 s · 17:42 » — les relevés d'une commande, pour l'interface (jamais dans l'image). */
export function commandFacts(command: AtelierProofCommand): string {
  const facts: string[] = [];
  if (command.status === 'not_run') {
    return 'non exécutée';
  }
  if (command.status === 'interrupted') {
    facts.push('interrompue');
  }
  if (command.exitCode !== null && command.exitCode !== undefined) {
    facts.push(`code ${command.exitCode}`);
  }
  if (command.durationMs !== null && command.durationMs !== undefined) {
    facts.push(`${(command.durationMs / 1000).toLocaleString('fr-FR', { maximumFractionDigits: 1, minimumFractionDigits: 1 })} s`);
  }
  if (command.startedAt) {
    const date = new Date(command.startedAt);
    if (!Number.isNaN(date.getTime())) {
      const pad = (value: number) => String(value).padStart(2, '0');
      facts.push(`${pad(date.getDate())}/${pad(date.getMonth() + 1)} ${pad(date.getHours())}:${pad(date.getMinutes())}`);
    }
  }
  if (command.truncated) {
    facts.push('sortie tronquée');
  }
  return facts.join(' · ');
}

/** Lit un blob en URL `data:` (pour un collage autonome dans Outlook ou Teams). */
function asDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob);
  });
}

function escapeHtml(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

/**
 * **Les captures de preuve** (F-212 / SF-212-04) : vignettes des fenêtres de terminal, zoom, « Copier
 * l’image » (presse-papiers image, à coller dans Teams ou un mail), « Télécharger », et les relevés des
 * commandes sous les vignettes — jamais dans l’image.
 *
 * <p>Joint à un encart message (`message`), il offre aussi « Copier le message avec les captures » :
 * le texte mis en forme et les images en un seul collage (text/html + text/plain).</p>
 */
@Component({
  selector: 'app-proof-captures',
  imports: [MatButtonModule, MatIconModule],
  template: `
    <section class="proof" [class.proof--attached]="!!message()" [attr.aria-label]="'Captures de preuve : ' + proof().title">
      <p class="proof__headline">
        <mat-icon aria-hidden="true">photo_camera</mat-icon>
        <span>{{ headline() }}</span>
      </p>
      <ul class="proof__shots">
        @for (capture of proof().captures; track capture.id; let n = $index) {
          <li class="proof__shot" [class.proof__shot--zoomed]="zoomed() === capture.id">
            @if (!broken().has(capture.id)) {
              <button type="button" class="proof__zoom" (click)="toggleZoom(capture.id)"
                      [attr.aria-pressed]="zoomed() === capture.id"
                      [attr.aria-label]="zoomed() === capture.id ? 'Réduire la capture ' + (n + 1) : 'Agrandir la capture ' + (n + 1)">
                <img class="proof__image" [src]="imageUrl(capture)" [alt]="'Capture ' + (n + 1) + ' : ' + proof().title"
                     (error)="markBroken(capture.id)" />
              </button>
              <div class="proof__actions">
                <button mat-button type="button" class="proof__copy" (click)="copyImage(capture, n)">
                  <mat-icon aria-hidden="true">content_copy</mat-icon>Copier l’image
                </button>
                <button mat-button type="button" class="proof__download" (click)="download(capture, n)">
                  <mat-icon aria-hidden="true">download</mat-icon>Télécharger
                </button>
              </div>
            } @else {
              <p class="proof__missing">Image indisponible.</p>
            }
          </li>
        }
      </ul>
      @if (message()) {
        <button mat-flat-button type="button" class="proof__copy-message" [disabled]="busy()" (click)="copyMessageWithCaptures()">
          <mat-icon aria-hidden="true">forward_to_inbox</mat-icon>Copier le message avec les captures
        </button>
      }
      <ul class="proof__commands" aria-label="Commandes exécutées">
        @for (command of proof().commands; track $index) {
          <li><code>$ {{ command.command }}</code> <span class="proof__facts">{{ facts(command) }}</span></li>
        }
      </ul>
      <p class="proof__origin">{{ origin() }}</p>
    </section>
  `,
  styles: `
    .proof {
      margin: var(--cg-space-2) 0;
      padding: var(--cg-space-2) var(--cg-space-3);
      border: 1px solid var(--cg-divider);
      border-left: 4px solid var(--cg-orange-2);
      border-radius: 8px;
      font-family: var(--cg-font-body);
      color: inherit;
    }

    .proof--attached {
      margin-top: var(--cg-space-1);
    }

    .proof__headline {
      display: flex;
      align-items: center;
      gap: var(--cg-space-1);
      margin: 0 0 var(--cg-space-2);
      font-weight: 600;
      color: var(--cg-orange-2);
      overflow-wrap: anywhere;
    }

    .proof__shots {
      display: flex;
      flex-wrap: wrap;
      gap: var(--cg-space-2);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .proof__shot {
      max-width: 360px;
      min-width: 0;
    }

    .proof__shot--zoomed {
      max-width: 100%;
      flex-basis: 100%;
    }

    .proof__zoom {
      display: block;
      padding: 0;
      border: 0;
      background: none;
      cursor: zoom-in;
      max-width: 100%;
    }

    .proof__shot--zoomed .proof__zoom {
      cursor: zoom-out;
    }

    .proof__image {
      display: block;
      max-width: 100%;
      height: auto;
      border-radius: 6px;
    }

    .proof__actions {
      display: flex;
      flex-wrap: wrap;
      gap: var(--cg-space-1);
    }

    .proof__copy-message {
      margin-top: var(--cg-space-2);
    }

    .proof__commands {
      margin: var(--cg-space-2) 0 0;
      padding: 0;
      list-style: none;
      font-size: 13px;
    }

    .proof__commands code {
      font-family: var(--cg-font-mono);
      overflow-wrap: anywhere;
    }

    .proof__facts,
    .proof__origin,
    .proof__missing {
      color: var(--cg-text-secondary);
      font-size: 12px;
    }

    .proof__origin {
      margin: var(--cg-space-1) 0 0;
    }
  `,
})
export class ProofCapturesComponent {
  private readonly http = inject(HttpClient);
  private readonly snackBar = inject(MatSnackBar);

  /** Les captures et leurs relevés. */
  readonly proof = input.required<AtelierProofBlock>();

  /** Terminal propriétaire des captures. */
  readonly workspaceId = input<string | null>(null);

  /** L’encart message que ces captures prouvent (F-212, usage cible) ; `null` dans le fil. */
  readonly message = input<CopyBlock | null>(null);

  readonly zoomed = signal<string | null>(null);
  readonly broken = signal<ReadonlySet<string>>(new Set());
  readonly busy = signal(false);

  readonly headline = computed(() => {
    const proof = this.proof();
    const step = proof.step ? ` · étape ${proof.step}` : '';
    return `Captures de preuve · ${proof.title}${step}`;
  });

  readonly origin = computed(() => {
    const proof = this.proof();
    const where = [proof.host, proof.shell].filter((part) => !!part).join(' · ');
    return (where ? where + ' — ' : '') + 'Capture générée à partir de la sortie réelle du poste.';
  });

  imageUrl(capture: AtelierProofCapture): string {
    return proofImageUrl(this.workspaceId(), capture.id);
  }

  facts(command: AtelierProofCommand): string {
    return commandFacts(command);
  }

  toggleZoom(id: string): void {
    this.zoomed.update((current) => (current === id ? null : id));
  }

  markBroken(id: string): void {
    this.broken.update((set) => new Set([...set, id]));
  }

  private fetchImage(capture: AtelierProofCapture): Promise<Blob> {
    return firstValueFrom(this.http.get(this.imageUrl(capture), { responseType: 'blob' }));
  }

  /** « Copier l’image » : presse-papiers image (PNG) ; repli « Télécharger » si non pris en charge. */
  copyImage(capture: AtelierProofCapture, index: number): void {
    const clipboard = navigator.clipboard;
    if (!clipboard || typeof clipboard.write !== 'function' || typeof ClipboardItem === 'undefined') {
      this.snackBar.open('Copie d’image impossible ici : l’image est téléchargée.', 'Fermer', { duration: 3000 });
      this.download(capture, index);
      return;
    }
    // La promesse est passée telle quelle à ClipboardItem : le geste de l’utilisateur reste valable
    // pendant le chargement (Safari l’exige).
    const png = this.fetchImage(capture).then((blob) => new Blob([blob], { type: 'image/png' }));
    clipboard.write([new ClipboardItem({ 'image/png': png })]).then(
      () => this.snackBar.open('Image copiée : collez-la dans Teams ou un mail.', 'Fermer', { duration: 2500 }),
      () => {
        this.snackBar.open('Copie d’image refusée par le navigateur : l’image est téléchargée.', 'Fermer', { duration: 3000 });
        this.download(capture, index);
      },
    );
  }

  /** « Télécharger » : le PNG, nommé d’après le titre. */
  download(capture: AtelierProofCapture, index: number): void {
    this.fetchImage(capture).then(
      (blob) => {
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = this.fileName(index);
        anchor.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
      },
      () => this.snackBar.open('Téléchargement impossible.', 'Fermer', { duration: 3000 }),
    );
  }

  fileName(index: number): string {
    const slug = this.proof().title
      .normalize('NFD')
      .replace(/[̀-ͯ]/g, '')
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-|-$/g, '')
      .slice(0, 60);
    return `preuve-${slug || 'terminal'}-${index + 1}.png`;
  }

  /**
   * « Copier le message avec les captures » : le corps mis en forme (comme « Copier ») suivi des images
   * en `data:` dans un seul text/html, et le texte brut en repli — un collage dans Outlook ou Teams.
   */
  async copyMessageWithCaptures(): Promise<void> {
    const block = this.message();
    const clipboard = navigator.clipboard;
    if (!block) {
      return;
    }
    const body = splitHeaders(block.content).body;
    if (!clipboard || typeof clipboard.write !== 'function' || typeof ClipboardItem === 'undefined') {
      this.snackBar.open('Copie impossible ici : copiez le message, puis chaque image.', 'Fermer', { duration: 3500 });
      return;
    }
    this.busy.set(true);
    try {
      const captures = this.proof().captures.filter((capture) => !this.broken().has(capture.id));
      const images = await Promise.all(captures.map((capture) => this.fetchImage(capture).then(asDataUrl)));
      const figures = images
        .map((src, n) => {
          const width = captures[n].width ? Math.round((captures[n].width as number) / 2) : null;
          const size = width ? ` width="${width}"` : '';
          return `<p><img src="${escapeHtml(src)}"${size} alt="Capture ${n + 1}"></p>`;
        })
        .join('');
      const html = clipboardHtml(body) + figures;
      await clipboard.write([
        new ClipboardItem({
          'text/html': new Blob([html], { type: 'text/html' }),
          'text/plain': new Blob([body], { type: 'text/plain' }),
        }),
      ]);
      this.snackBar.open('Message et captures copiés : collez dans Outlook ou Teams.', 'Fermer', { duration: 2500 });
    } catch {
      this.snackBar.open('Copie impossible : copiez le message, puis chaque image.', 'Fermer', { duration: 3500 });
    } finally {
      this.busy.set(false);
    }
  }
}
