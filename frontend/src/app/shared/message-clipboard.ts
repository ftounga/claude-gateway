import { renderMarkdown } from './markdown.pipe';

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
 * Une image jointe à un message copié (F-212 / SF-212-05) : le chargeur est fourni par l'appelant
 * (route isolée du propriétaire), le composant de copie ne connaît aucune URL ni aucun backend.
 */
export interface ClipboardImage {
  /** Texte alternatif de l'image collée (« Capture 1 »), sans mention de génération. */
  alt: string;
  /** Largeur d'affichage en px (la moitié de la largeur réelle : rendu net sur écran dense), ou `null`. */
  width: number | null;
  /** Charge l'image (PNG). */
  load: () => Promise<Blob>;
}

/** Lit un blob en URL `data:` (collage autonome : aucune URL de l'application ne part avec le message). */
export function blobToDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob);
  });
}

function escapeAttribute(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

/** Le corps mis en forme suivi d'une image par paragraphe — le `text/html` d'un message avec captures. */
export function messageWithImagesHtml(body: string, images: readonly { src: string; width: number | null; alt: string }[]): string {
  const figures = images
    .map((image) => {
      const size = image.width ? ` width="${image.width}"` : '';
      return `<p><img src="${escapeAttribute(image.src)}"${size} alt="${escapeAttribute(image.alt)}"></p>`;
    })
    .join('');
  return clipboardHtml(body) + figures;
}

/**
 * **Copier le message avec ses images** (F-212 / SF-212-05) : UNE entrée de presse-papiers portant
 * `text/html` (corps mis en forme + images en `data:`) et `text/plain` (corps seul). Pas d'entrée
 * `image/png` : sa présence fait voir un fichier à l'éditeur cible, qui peut alors coller l'image seule
 * et perdre le texte (voir la mini-spec SF-212-05).
 *
 * <p>Le HTML est passé en promesse à `ClipboardItem` : le geste de l'utilisateur reste valable pendant
 * le chargement des images. Une image qui ne se charge pas est omise. Rend le nombre d'images copiées ;
 * rejette si le navigateur refuse l'écriture.</p>
 */
export async function writeMessageWithImages(clipboard: Clipboard, body: string, images: readonly ClipboardImage[]): Promise<number> {
  let copied = 0;
  const html = Promise.allSettled(
    images.map((image) =>
      image
        .load()
        .then((blob) => blobToDataUrl(blob.type.startsWith('image/') ? blob : new Blob([blob], { type: 'image/png' }))),
    ),
  ).then((results) => {
    const loaded = results.flatMap((result, n) =>
      result.status === 'fulfilled' ? [{ src: result.value, width: images[n].width, alt: images[n].alt }] : [],
    );
    copied = loaded.length;
    return new Blob([messageWithImagesHtml(body, loaded)], { type: 'text/html' });
  });
  await clipboard.write([
    new ClipboardItem({
      'text/html': html,
      'text/plain': new Blob([body], { type: 'text/plain' }),
    }),
  ]);
  await html;
  return copied;
}
