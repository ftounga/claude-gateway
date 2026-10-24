/**
 * F-215 / SF-215-03 — les icônes OFFICIELLES (AWS, Azure, GCP, Kubernetes, on-prem, génériques) pour les cours
 * vidéo, lues dans la lib `diagrams` déjà installée pour F-142. Le poste du client n'installe rien : la gateway les
 * demande ici et les dépose dans le dossier de la composition.
 *
 * Un nom d'icône est son chemin sous `resources/`, sans extension : `aws/network/elb-application-load-balancer`.
 * Liste blanche de fournisseurs, segments sûrs, aucune traversée de chemin, taille bornée.
 */

"use strict";

const fs = require("node:fs");
const path = require("node:path");
const { execFileSync } = require("node:child_process");

const PROVIDERS = ["aws", "azure", "gcp", "k8s", "onprem", "generic"];
const MAX_NAMES = 40;
const MAX_RESULTS = 40;
const MAX_ICON_BYTES = 512 * 1024;
const MAX_BODY_BYTES = 16 * 1024;
const NAME = /^[a-z0-9]+(\/[a-z0-9][a-z0-9._-]*){1,3}$/;

class IconError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

let cachedRoot;
let cachedIndex;

/** Le dossier `resources/` de la lib diagrams : ICONS_DIR, sinon demandé à python. */
function root() {
  if (cachedRoot !== undefined) {
    return cachedRoot;
  }
  if (process.env.ICONS_DIR) {
    cachedRoot = process.env.ICONS_DIR;
    return cachedRoot;
  }
  try {
    const out = execFileSync(process.env.PYTHON_BIN || "python3", ["-c",
      "import diagrams, os; print(os.path.join(os.path.dirname(os.path.dirname(diagrams.__file__)), 'resources'))"],
    { encoding: "utf8", timeout: 10000 });
    cachedRoot = out.trim();
  } catch (e) {
    cachedRoot = "";
  }
  return cachedRoot;
}

/** Pour les tests : oublie la racine et l'index. */
function reset() {
  cachedRoot = undefined;
  cachedIndex = undefined;
}

/** Normalise un nom (minuscules, sans `.png`) ou rend null s'il n'est pas sûr. */
function normalize(raw) {
  if (typeof raw !== "string") {
    return null;
  }
  const name = raw.trim().toLowerCase().replace(/\\/g, "/").replace(/\.png$/, "");
  if (!NAME.test(name) || name.split("/").some((s) => s === "." || s === "..")) {
    return null;
  }
  return PROVIDERS.includes(name.split("/")[0]) ? name : null;
}

/** Tous les noms d'icônes des fournisseurs permis (calculé une fois). */
function index() {
  if (cachedIndex) {
    return cachedIndex;
  }
  const base = root();
  const names = [];
  const walk = (dir, prefix, depth) => {
    let entries;
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch (e) {
      return;
    }
    for (const entry of entries) {
      if (entry.isDirectory() && depth < 3) {
        walk(path.join(dir, entry.name), prefix + "/" + entry.name, depth + 1);
      } else if (entry.isFile() && entry.name.endsWith(".png")) {
        const name = normalize(prefix + "/" + entry.name);
        if (name) {
          names.push(name);
        }
      }
    }
  };
  if (base) {
    for (const provider of PROVIDERS) {
      walk(path.join(base, provider), provider, 0);
    }
  }
  names.sort();
  cachedIndex = names;
  return names;
}

/** Les noms qui contiennent TOUS les mots de la recherche (le plus court d'abord). */
function search(query) {
  const words = String(query || "").toLowerCase().split(/[\s,;]+/).filter((w) => w.length > 0);
  if (words.length === 0 || String(query).length > 60) {
    throw new IconError(400, "chercher : 1 à 60 caractères.");
  }
  return index().filter((name) => words.every((w) => name.includes(w)))
    .sort((a, b) => a.length - b.length || a.localeCompare(b))
    .slice(0, MAX_RESULTS);
}

/** Lit les icônes demandées : { icones: [{nom, png}], manquantes: [...], invalides: [...] }. */
function read(names) {
  if (!Array.isArray(names) || names.length === 0) {
    throw new IconError(400, "noms : une liste d'icônes.");
  }
  if (names.length > MAX_NAMES) {
    throw new IconError(400, "Trop d'icônes (" + MAX_NAMES + " au plus).");
  }
  const base = root();
  const icones = [];
  const manquantes = [];
  const invalides = [];
  for (const raw of names) {
    const name = normalize(raw);
    if (!name) {
      invalides.push(String(raw).slice(0, 120));
      continue;
    }
    const file = path.join(base, name + ".png");
    if (!base || !path.resolve(file).startsWith(path.resolve(base) + path.sep)) {
      manquantes.push(name);
      continue;
    }
    try {
      const stat = fs.statSync(file);
      if (!stat.isFile() || stat.size > MAX_ICON_BYTES) {
        manquantes.push(name);
        continue;
      }
      icones.push({ nom: name, png: fs.readFileSync(file).toString("base64") });
    } catch (e) {
      manquantes.push(name);
    }
  }
  return { icones, manquantes, invalides };
}

/** Le corps d'une requête `POST /icons` : `{ chercher }` ou `{ noms: [...] }`. */
function handle(payload) {
  if (!payload || typeof payload !== "object") {
    throw new IconError(400, "Corps JSON attendu.");
  }
  if (!root() || index().length === 0) {
    throw new IconError(503, "Icônes indisponibles : la lib diagrams est introuvable.");
  }
  if (payload.chercher !== undefined && payload.chercher !== null && payload.chercher !== "") {
    return { trouvees: search(payload.chercher) };
  }
  return read(payload.noms);
}

module.exports = { PROVIDERS, MAX_NAMES, MAX_BODY_BYTES, MAX_ICON_BYTES, IconError, normalize, search, read, handle, reset };
