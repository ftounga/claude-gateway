import { Component, OnDestroy, computed, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { CopyBlock } from '../../shared/copy-block.model';
import { MarkdownPipe, renderMarkdown } from '../../shared/markdown.pipe';
import { isMessageBlockType, splitHeaders } from '../../shared/message-segments';

/** Attributs de présentation retirés du HTML copié (F-203) : Outlook / Teams reçoivent des balises simples. */
const PRESENTATION_ATTRIBUTES = ['class', 'style', 'id'];

/**
 * HTML « propre » pour le presse-papiers (F-203 / SF-203-02) : le Markdown rendu et assaini
 * (DOMPurify, via {@link renderMarkdown}), débarrassé des attributs de présentation pour que le
 * collage dans Outlook ou Teams garde la mise en forme (gras, italique, listes, liens) sans hériter
 * de styles de l'application. Le conteneur n'est jamais attaché au document.
 */
export function clipboardHtml(markdown: string): string {
  const container = document.createElement('div');
  container.innerHTML = renderMarkdown(markdown);
  container.querySelectorAll('*').forEach((element) => {
    PRESENTATION_ATTRIBUTES.forEach((attribute) => element.removeAttribute(attribute));
  });
  return container.innerHTML;
}

/**
 * Bloc copiable inline façon ChatGPT (F-26). Affiche l'en-tête (icône + libellé du type/langage),
 * le contenu **brut** en monospace, et un bouton « Copier » qui écrit le contenu dans le
 * presse-papiers.
 *
 * <p>F-203 : sert aussi d'<b>encart message</b> dans le terminal de l'Atelier — objet présenté à
 * part, corps copié en texte riche « propre », feedback « Copié », peau sombre ({@link tone}).</p>
 *
 * <p>Composant de présentation pur : il ne charge aucune donnée et n'appelle aucun backend ni
 * fournisseur IA. Le bloc lui est fourni via l'entrée {@link block}. Le code est rendu par
 * interpolation de texte Angular ; les blocs riches passent par le pipe `markdown` (DOMPurify).</p>
 */
@Component({
  selector: 'app-copy-block',
  imports: [MatButtonModule, MatIconModule, MatTooltipModule, MarkdownPipe],
  templateUrl: './copy-block.component.html',
  styleUrl: './copy-block.component.scss',
  host: { '[class.copy-block-host--dark]': "tone() === 'dark'" },
})
export class CopyBlockComponent implements OnDestroy {
  private readonly snackBar = inject(MatSnackBar);

  /** Bloc copiable à présenter (déjà extrait du message par le parent). */
  readonly block = input.required<CopyBlock>();

  /**
   * Peau de l'encart (F-203) : `dark` reprend la peau sombre de charte (déjà portée sous 820 px) pour
   * le terminal de l'Atelier. `light` (défaut) : rendu F-26 inchangé.
   */
  readonly tone = input<'light' | 'dark'>('light');

  /**
   * « Marquer envoyé » (F-211) : vrai quand l'encart désigne un bloc d'une réponse persistée — le
   * parent sait alors le repérer. Faux (défaut) : aucun bouton, rendu F-26 / F-203 inchangé.
   */
  readonly sendable = input(false);

  /** Instant de l'envoi marqué (ISO), ou `null` s'il n'est pas marqué (F-211). */
  readonly sentAt = input<string | null>(null);

  /** Vrai pendant l'appel au serveur : les gestes d'envoi sont désactivés (F-211). */
  readonly sendBusy = input(false);

  /** Lecture seule (F-211) : l'état « Envoyé » se lit, sans bouton ni « Annuler ». */
  readonly sendReadOnly = input(false);

  /** [Marquer envoyé] (F-211). */
  readonly markSent = output<void>();

  /** [Annuler] l'envoi marqué (F-211). */
  readonly cancelSent = output<void>();

  /** « 24/10 17:42 » — l'heure locale de l'envoi marqué. */
  readonly sentLabel = computed(() => {
    const at = this.sentAt();
    if (!at) {
      return null;
    }
    const date = new Date(at);
    if (Number.isNaN(date.getTime())) {
      return null;
    }
    const pad = (value: number) => String(value).padStart(2, '0');
    return `${pad(date.getDate())}/${pad(date.getMonth() + 1)} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
  });

  /** Vrai pendant 2 s après une copie réussie : le bouton affiche « Copié ». */
  readonly copied = signal(false);
  private copiedTimer: ReturnType<typeof setTimeout> | null = null;

  /**
   * Objet et corps (F-203) : pour un e-mail ou un message, une première ligne « Objet : … » est
   * présentée à part et n'est pas copiée avec le corps. Les autres blocs : corps = contenu.
   */
  readonly parts = computed(() => {
    const block = this.block();
    return isMessageBlockType(block.type)
      ? splitHeaders(block.content)
      : { subject: null, recipient: null, body: block.content };
  });

  /** Vrai pour un encart mail/message : seuls ceux-là se marquent envoyés (F-211). */
  isMessage(): boolean {
    return isMessageBlockType(this.block().type);
  }

  /** Icône Material par type de bloc. */
  private static readonly TYPE_ICONS = {
    code: 'code',
    doc: 'description',
    mail: 'mail_outline',
    message: 'chat_bubble_outline',
  } as const;

  iconFor(type: CopyBlock['type']): string {
    return CopyBlockComponent.TYPE_ICONS[type];
  }

  /**
   * Bloc « riche » (document / e-mail / message) : rendu en Markdown formaté et copié en <b>texte
   * enrichi</b> (gras, italique, listes… préservés au collage dans Word, Outlook, Teams…). Le code,
   * lui, reste brut.
   */
  isRich(): boolean {
    const type = this.block().type;
    return type === 'doc' || type === 'mail' || type === 'message';
  }

  /**
   * Copie le bloc (son corps, sans l'objet). Pour un bloc riche : écrit à la fois le HTML propre
   * (mise en forme préservée) et le texte brut de repli. Pour du code : texte brut uniquement.
   * Erreur douce si le presse-papiers est indisponible.
   */
  copy(): void {
    const clipboard = navigator.clipboard;
    if (!clipboard || typeof clipboard.writeText !== 'function') {
      this.snackBar.open('Copie impossible dans ce contexte.', 'Fermer', { duration: 3000 });
      return;
    }
    const content = this.parts().body;
    const ok = () => {
      this.markCopied();
      this.snackBar.open('Contenu copié.', 'Fermer', { duration: 2000 });
    };
    const ko = () => this.snackBar.open('Copie impossible dans ce contexte.', 'Fermer', { duration: 3000 });

    // Copie enrichie (HTML) pour les blocs riches, si l'API le permet ; sinon repli sur le texte brut.
    if (this.isRich() && typeof clipboard.write === 'function' && typeof ClipboardItem !== 'undefined') {
      const html = clipboardHtml(content);
      const item = new ClipboardItem({
        'text/html': new Blob([html], { type: 'text/html' }),
        'text/plain': new Blob([content], { type: 'text/plain' }),
      });
      clipboard.write([item]).then(ok, () => clipboard.writeText(content).then(ok, ko));
      return;
    }
    clipboard.writeText(content).then(ok, ko);
  }

  /** Copie l'objet seul, en texte brut (F-203) — à coller dans le champ Objet du client de messagerie. */
  copySubject(): void {
    const subject = this.parts().subject;
    const clipboard = navigator.clipboard;
    if (!subject || !clipboard || typeof clipboard.writeText !== 'function') {
      this.snackBar.open('Copie impossible dans ce contexte.', 'Fermer', { duration: 3000 });
      return;
    }
    clipboard.writeText(subject).then(
      () => this.snackBar.open('Objet copié.', 'Fermer', { duration: 2000 }),
      () => this.snackBar.open('Copie impossible dans ce contexte.', 'Fermer', { duration: 3000 }),
    );
  }

  ngOnDestroy(): void {
    if (this.copiedTimer) {
      clearTimeout(this.copiedTimer);
    }
  }

  private markCopied(): void {
    this.copied.set(true);
    if (this.copiedTimer) {
      clearTimeout(this.copiedTimer);
    }
    this.copiedTimer = setTimeout(() => this.copied.set(false), 2000);
  }
}
