/**
 * Le PDF d'une page (F-184 / SF-184-01) — la partie PURE : validation du lot, table des ressources,
 * feuille d'impression, en-tête des manquants.
 *
 * Le service reste HORS LIGNE (NetworkPolicy : DNS seul). La gateway lui remet un LOT — le HTML de la
 * page et chaque ressource qu'elle référence — et le serveur l'imprime dans chromium en interceptant
 * chaque requête : servie depuis le lot, ou refusée. Rien ne sort.
 *
 * Aucune dépendance à chromium ici : ces fonctions se testent avec `node --test`, sans conteneur.
 */

"use strict";

/** L'origine VIRTUELLE de la page : un nom relatif (`capture.png`) s'y résout, la gateway le sait. */
const ORIGIN = "https://page.cg.local";
const PAGE_URL = ORIGIN + "/index.html";

const MAX_HTML_BYTES = 8 * 1024 * 1024;
const MAX_RESOURCES = 60;
const MAX_BODY_BYTES = 32 * 1024 * 1024;
const MAX_PDF_BYTES = 20 * 1024 * 1024;
const MAX_MISSING_HEADER_CHARS = 1000;

/** A4, marges d'impression : 16 mm en haut et en bas, 14 mm sur les côtés. */
const PDF_OPTIONS = Object.freeze({
  format: "A4",
  printBackground: true,
  preferCSSPageSize: false,
  margin: { top: "16mm", bottom: "16mm", left: "14mm", right: "14mm" },
});

/**
 * La feuille ajoutée à l'impression. Elle ne touche PAS à la charte de la page : elle imprime les
 * fonds (sinon les surfaces colorées disparaissent) et évite de couper un bloc en deux.
 */
const PRINT_CSS = [
  "html, body { -webkit-print-color-adjust: exact; print-color-adjust: exact; }",
  "img, svg, figure, pre, table tr, .mermaid, .cg-mermaid, blockquote { break-inside: avoid; }",
  "h1, h2, h3, h4, h5, h6 { break-after: avoid; }",
].join("\n");

/** Une erreur de lot : porte le code HTTP à rendre. */
class LotError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

/** Une URL comparable : sans fragment. Renvoie `null` si ce n'est pas une URL http(s). */
function normalizeUrl(raw) {
  if (typeof raw !== "string" || raw.length === 0 || raw.length > 4096) {
    return null;
  }
  let url;
  try {
    url = new URL(raw);
  } catch {
    return null;
  }
  if (url.protocol !== "https:" && url.protocol !== "http:") {
    return null;
  }
  url.hash = "";
  return url.toString();
}

const BASE64 = /^[A-Za-z0-9+/]*={0,2}$/;

/**
 * Valide le lot et construit la table `url → { contentType, body }`, la page comprise.
 *
 * @throws {LotError} 400 si le lot est mal formé, 413 s'il dépasse une borne
 */
function parseLot(payload) {
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
    throw new LotError(400, "Le lot est manquant.");
  }
  const html = payload.html;
  if (typeof html !== "string" || html.trim().length === 0) {
    throw new LotError(400, "Le HTML de la page est manquant (champ « html »).");
  }
  const htmlBytes = Buffer.byteLength(html, "utf-8");
  if (htmlBytes > MAX_HTML_BYTES) {
    throw new LotError(413, "Page trop lourde : " + htmlBytes + " octets (8 Mo au plus).");
  }
  const resources = payload.resources === undefined ? [] : payload.resources;
  if (!Array.isArray(resources)) {
    throw new LotError(400, "Le champ « resources » doit être une liste.");
  }
  if (resources.length > MAX_RESOURCES) {
    throw new LotError(413, "Trop de ressources : " + resources.length + " (" + MAX_RESOURCES + " au plus).");
  }
  const table = new Map();
  table.set(PAGE_URL, { contentType: "text/html; charset=utf-8", body: Buffer.from(html, "utf-8") });
  resources.forEach((resource, index) => {
    const where = "Ressource n° " + (index + 1);
    if (!resource || typeof resource !== "object") {
      throw new LotError(400, where + " : entrée invalide.");
    }
    const url = normalizeUrl(resource.url);
    if (!url) {
      throw new LotError(400, where + " : adresse http(s) invalide.");
    }
    const contentType = typeof resource.contentType === "string" && resource.contentType.trim()
      ? resource.contentType.trim().slice(0, 200)
      : "application/octet-stream";
    if (typeof resource.body !== "string" || !BASE64.test(resource.body)) {
      throw new LotError(400, where + " : contenu base64 invalide.");
    }
    table.set(url, { contentType, body: Buffer.from(resource.body, "base64") });
  });
  return table;
}

/** La ressource du lot pour cette requête, ou `null` : la requête sera refusée. */
function lookup(table, rawUrl) {
  const url = normalizeUrl(rawUrl);
  return url ? table.get(url) || null : null;
}

/** L'en-tête `X-Cg-Missing-Resources` : URL refusées, dédoublonnées, tronquées. ASCII seulement. */
function missingHeader(missing) {
  const unique = [...new Set(missing)].map((u) => encodeURI(decodeSafe(u)));
  let out = unique.join(", ");
  if (out.length > MAX_MISSING_HEADER_CHARS) {
    out = out.slice(0, MAX_MISSING_HEADER_CHARS - 3) + "...";
  }
  return out.replace(/[^\x20-\x7E]/g, "");
}

function decodeSafe(u) {
  try {
    return decodeURI(u);
  } catch {
    return u;
  }
}

module.exports = {
  ORIGIN,
  PAGE_URL,
  MAX_BODY_BYTES,
  MAX_PDF_BYTES,
  PDF_OPTIONS,
  PRINT_CSS,
  LotError,
  normalizeUrl,
  parseLot,
  lookup,
  missingHeader,
};
