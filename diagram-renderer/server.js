/**
 * Le service de rendu de diagrammes (F-142 / SF-142-06).
 *
 * Il fait une seule chose : recevoir du code Mermaid, rendre une image, la renvoyer. Il tourne DANS
 * NOTRE CLUSTER pour que le poste du client n'ait rien à installer — c'est tout l'objet de cette
 * subfeature. Il n'est pas exposé hors du cluster (Service ClusterIP) et ne parle à personne d'autre.
 *
 * Ce qu'il ne fait pas : il ne garde rien, n'écrit rien de durable, ne connaît aucun compte. Le code
 * d'un diagramme entre, une image sort.
 */
const http = require("node:http");
const { execFile } = require("node:child_process");
const { mkdtemp, writeFile, readFile, rm } = require("node:fs/promises");
const { tmpdir } = require("node:os");
const path = require("node:path");
const drawio = require("./drawio.js");

/** Bornes — les mêmes que celles annoncées côté gateway, pour que le refus soit cohérent des deux côtés. */
const MAX_CODE_CHARS = 20000;
const MAX_IMAGE_BYTES = 8 * 1024 * 1024;
const RENDER_TIMEOUT_MS = 30000;
const DEFAULT_WIDTH = 1600;
/**
 * Facteur d'échelle du rendu. `-w` fixe la largeur de la PAGE, pas celle de l'image : un petit
 * diagramme sort en 170 px de large, illisible une fois posé dans une slide. Le scale multiplie la
 * définition réelle — c'est ce qui rend le PNG net en projection.
 */
const DEFAULT_SCALE = 3;
const MAX_SCALE = 5;
const MAX_BODY_BYTES = 256 * 1024;
/** Un deck porte ses images encodées : son corps est plus gros, et son temps de construction plus long. */
const MAX_DECK_BODY_BYTES = 24 * 1024 * 1024;
const MAX_DECK_BYTES = 8 * 1024 * 1024;
const DECK_TIMEOUT_MS = 60000;

/**
 * L'aperçu du deck (F-129 / SF-129-06) : une image par slide, rendue ICI avec le chromium que le
 * service a déjà pour Mermaid. Le poste du client n'installe ni LibreOffice ni pdftoppm — c'était
 * le dernier maillon qui lui restait à installer pour que la présentation soit lisible dans l'app.
 */
const PREVIEW_WIDTH = 1280;
const PREVIEW_HEIGHT = 720;
const MAX_PREVIEW_SLIDES = 30;
const MAX_PREVIEW_BYTES = 2 * 1024 * 1024;
const PREVIEW_TIMEOUT_MS = 20000;
/** Trois rendus de front : au-delà, les chromium se disputent la mémoire du conteneur. */
const PREVIEW_CONCURRENCY = 3;
/**
 * F-142 / SF-142-13 — au-delà de ce budget de pixels, l'aperçu du `.drawio` se rend à l'échelle 1 :
 * doubler la définition d'un très grand schéma produirait une image plus lourde que sa borne.
 */
const DRAWIO_SCALE_BUDGET = 2_000_000;
const CHROME = process.env.CHROME_BIN || "chromium";
/** Les programmes appelés : figés dans l'image, ouverts par l'environnement pour pouvoir ÊTRE TESTÉS. */
const PYTHON = process.env.PYTHON_BIN || "python3";
const DECK_SCRIPT = process.env.DECK_SCRIPT || "/app/deck.py";
const OFFICE_SCRIPT = process.env.OFFICE_SCRIPT || "/app/office.py";

/**
 * Le document Word et le classeur Excel (F-129 / SF-129-07) : même moteur que le deck, même refus.
 * Une DESCRIPTION entre sur l'entrée standard du programme, un fichier sort — jamais du code.
 */
const OFFICE_FORMATS = {
  docx: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  xlsx: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
};
const MAX_OFFICE_BYTES = 8 * 1024 * 1024;

const PORT = Number(process.env.PORT || 8080);

/** La configuration puppeteer : chromium du système, sans bac à sable (on est déjà dans un conteneur). */
const PUPPETEER_CONFIG = "/app/puppeteer.json";

function send(res, status, body, type) {
  res.writeHead(status, { "Content-Type": type, "Content-Length": Buffer.byteLength(body) });
  res.end(body);
}

function fail(res, status, message) {
  send(res, status, JSON.stringify({ error: message }), "application/json; charset=utf-8");
}

async function readBody(req, max = MAX_BODY_BYTES) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > max) {
      throw new Error("corps trop volumineux");
    }
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString("utf-8");
}

/**
 * Le rendu d'une architecture cloud avec les icônes officielles (F-142 / SF-142-07).
 *
 * Le programme Python reçoit la DESCRIPTION sur son entrée standard — jamais du code. C'est la
 * décision de la subfeature : `diagrams` se pilote en écrivant du Python, et exécuter le Python d'un
 * modèle sur notre infrastructure serait une porte qu'on n'ouvre pas.
 */
function renderCloud(spec, outputBase) {
  return new Promise((resolve, reject) => {
    const child = execFile("python3", ["/app/cloud.py"], { timeout: RENDER_TIMEOUT_MS },
      (error, stdout, stderr) => {
        if (error) {
          reject(new Error((stderr || error.message || "").toString().slice(0, 500)));
          return;
        }
        // Le générateur écrit le FICHIER sur la première ligne ; la seconde, quand elle existe, nomme
        // les types rendus SANS icône officielle (F-142 / SF-142-09). Il faut les distinguer : tout
        // prendre pour un chemin faisait échouer un rendu pourtant réussi.
        const lines = (stdout || "").split("\n").map((l) => l.trim()).filter(Boolean);
        const marked = (l) => l.startsWith("UNKNOWN_TYPES=") || l.startsWith("NOTICE=");
        const file = lines.find((l) => !marked(l)) || "";
        const marker = lines.find((l) => l.startsWith("UNKNOWN_TYPES="));
        // F-142 / SF-142-17 : la note de densité suit la même convention — une ligne marquée.
        const notice = lines.find((l) => l.startsWith("NOTICE="));
        resolve({
          file,
          unknown: marker ? marker.slice("UNKNOWN_TYPES=".length) : "",
          notice: notice ? notice.slice("NOTICE=".length) : "",
        });
      });
    child.stdin.end(JSON.stringify({ ...spec, output: outputBase }), "utf-8");
  });
}

function renderWithMermaid(input, output, format, width, scale) {
  return new Promise((resolve, reject) => {
    const args = ["-i", input, "-o", output, "-b", "transparent", "-w", String(width),
      "-s", String(scale), "-p", PUPPETEER_CONFIG];
    if (format === "svg") {
      args.push("-e", "svg");
    }
    execFile("mmdc", args, { timeout: RENDER_TIMEOUT_MS }, (error, stdout, stderr) => {
      if (error) {
        // Le message du moteur est repris tel quel : « diagramme invalide » sans la raison
        // n'aide personne à corriger son code.
        reject(new Error((stderr || stdout || error.message || "").toString().slice(0, 500)));
        return;
      }
      resolve();
    });
  });
}

async function render(req, res) {
  let payload;
  try {
    payload = JSON.parse(await readBody(req) || "{}");
  } catch (e) {
    return fail(res, 400, "Corps illisible : " + e.message);
  }
  if (payload.engine === "cloud") {
    return renderCloudRequest(payload, res);
  }
  if (payload.engine === "drawio") {
    return renderDrawioRequest(payload, res);
  }
  const code = typeof payload.code === "string" ? payload.code.trim() : "";
  const format = payload.format === "svg" ? "svg" : "png";
  const width = Number.isFinite(payload.width) ? Math.min(Math.max(payload.width, 200), 4000) : DEFAULT_WIDTH;
  // Le SVG est vectoriel : le mettre à l'échelle n'apporte rien, et alourdirait le fichier.
  const scale = format === "svg" ? 1
    : (Number.isFinite(payload.scale) ? Math.min(Math.max(payload.scale, 1), MAX_SCALE) : DEFAULT_SCALE);
  if (!code) {
    return fail(res, 400, "Aucun code de diagramme.");
  }
  if (code.length > MAX_CODE_CHARS) {
    return fail(res, 400, "Diagramme trop long : " + code.length + " caracteres pour un maximum de "
      + MAX_CODE_CHARS + ".");
  }
  const dir = await mkdtemp(path.join(tmpdir(), "cg-diagram-"));
  try {
    const input = path.join(dir, "diagram.mmd");
    const output = path.join(dir, "diagram." + format);
    await writeFile(input, code, "utf-8");
    await renderWithMermaid(input, output, format, width, scale);
    const image = await readFile(output);
    if (image.length > MAX_IMAGE_BYTES) {
      return fail(res, 413, "Image rendue trop lourde : " + image.length + " octets.");
    }
    res.writeHead(200, {
      "Content-Type": format === "svg" ? "image/svg+xml" : "image/png",
      "Content-Length": image.length,
    });
    res.end(image);
  } catch (e) {
    return fail(res, 422, "Le diagramme n'a pas pu etre rendu : " + (e.message || "raison inconnue"));
  } finally {
    await rm(dir, { recursive: true, force: true }).catch(() => undefined);
  }
}

/**
 * La construction d'un deck (F-129 / SF-129-05) : une description entre, un .pptx sort.
 *
 * Ici aussi, le programme Python reçoit des DONNÉES sur son entrée standard — jamais du code.
 */
function buildDeck(spec, outputBase) {
  return new Promise((resolve, reject) => {
    const child = execFile(PYTHON, [DECK_SCRIPT], { timeout: DECK_TIMEOUT_MS, maxBuffer: 1 << 20 },
      (error, stdout, stderr) => {
        if (error) {
          reject(new Error((stderr || error.message || "").toString().slice(0, 500)));
          return;
        }
        // Le fichier sur la première ligne ; puis une ligne « PREVIEW= » par slide, DANS L'ORDRE
        // (F-129 / SF-129-06). Le marqueur suit la convention déjà posée par cloud.py.
        const lines = (stdout || "").split("\n").map((l) => l.trim()).filter(Boolean);
        const file = lines.find((l) => !l.startsWith("PREVIEW=")) || "";
        const pages = lines.filter((l) => l.startsWith("PREVIEW="))
          .map((l) => l.slice("PREVIEW=".length));
        resolve({ file, pages });
      });
    child.stdin.end(JSON.stringify({ ...spec, output: outputBase }), "utf-8");
  });
}

/** Une page d'aperçu -> un PNG. Un profil par rendu : trois chromium ne partagent pas un profil. */
function shoot(page, output, profile) {
  return new Promise((resolve, reject) => {
    const args = ["--headless=new", "--no-sandbox", "--disable-gpu", "--disable-dev-shm-usage",
      "--hide-scrollbars", "--force-device-scale-factor=1", "--virtual-time-budget=3000",
      `--user-data-dir=${profile}`, `--window-size=${PREVIEW_WIDTH},${PREVIEW_HEIGHT}`,
      `--screenshot=${output}`, "file://" + page];
    execFile(CHROME, args, { timeout: PREVIEW_TIMEOUT_MS }, (error, stdout, stderr) => {
      if (error) {
        reject(new Error((stderr || error.message || "").toString().slice(0, 300)));
        return;
      }
      resolve(output);
    });
  });
}

/**
 * Rend toutes les pages, par paquets de {@link PREVIEW_CONCURRENCY}, et renvoie les PNG DANS
 * L'ORDRE des slides — un aperçu dont les slides sont mélangées est pire que pas d'aperçu.
 */
async function shootAll(pages, dir) {
  const shots = new Array(pages.length);
  let next = 0;
  async function worker(slot) {
    while (next < pages.length) {
      const index = next++;
      const output = path.join(dir, `apercu-${String(index + 1).padStart(3, "0")}.png`);
      await shoot(pages[index], output, path.join(dir, `profil-${slot}`));
      const png = await readFile(output);
      if (png.length > MAX_PREVIEW_BYTES) {
        throw new Error(`Image d'aperçu trop lourde (${png.length} octets).`);
      }
      shots[index] = png.toString("base64");
    }
  }
  const slots = Math.min(PREVIEW_CONCURRENCY, pages.length);
  await Promise.all(Array.from({ length: slots }, (_, slot) => worker(slot)));
  return shots;
}

async function presentation(req, res) {
  let payload;
  try {
    payload = JSON.parse(await readBody(req, MAX_DECK_BODY_BYTES) || "{}");
  } catch (e) {
    return fail(res, 400, "Corps illisible : " + e.message);
  }
  const spec = payload.spec;
  if (!spec || typeof spec !== "object" || Array.isArray(spec)) {
    return fail(res, 400, "La description du deck est manquante (champ « spec »).");
  }
  const wantsPreview = spec.preview === true;
  if (wantsPreview && Array.isArray(spec.slides) && spec.slides.length > MAX_PREVIEW_SLIDES) {
    return fail(res, 422, `Aperçu refusé : ${spec.slides.length} slides, maximum `
      + `${MAX_PREVIEW_SLIDES}. Le fichier se produit sans aperçu (« preview »: false).`);
  }
  const dir = await mkdtemp(path.join(tmpdir(), "cg-deck-"));
  try {
    const produced = await buildDeck(spec, path.join(dir, "deck"));
    const file = await readFile(produced.file || path.join(dir, "deck.pptx"));
    if (file.length > MAX_DECK_BYTES) {
      return fail(res, 413, "Présentation trop lourde : " + file.length + " octets.");
    }
    if (!wantsPreview) {
      res.writeHead(200, {
        "Content-Type": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "Content-Length": file.length,
      });
      res.end(file);
      return;
    }
    // L'aperçu ne fait JAMAIS échouer le deck : le fichier part, et l'échec du rendu est DIT.
    let slides = [];
    let previewError = "";
    try {
      slides = await shootAll(produced.pages, dir);
    } catch (e) {
      slides = [];
      previewError = (e.message || "rendu d'aperçu en échec").slice(0, 300);
    }
    const body = JSON.stringify({ pptx: file.toString("base64"), slides, previewError });
    send(res, 200, body, "application/json; charset=utf-8");
  } catch (e) {
    return fail(res, 422, (e.message || "Le deck n'a pas pu etre construit.").slice(0, 500));
  } finally {
    await rm(dir, { recursive: true, force: true }).catch(() => undefined);
  }
}

/**
 * La construction d'un document Office (F-129 / SF-129-07) : une description entre, un fichier sort.
 * Le programme Python reçoit des DONNÉES sur son entrée standard — jamais du code, comme partout ici.
 */
function buildOffice(spec, outputBase) {
  return new Promise((resolve, reject) => {
    const child = execFile(PYTHON, [OFFICE_SCRIPT], { timeout: DECK_TIMEOUT_MS, maxBuffer: 1 << 20 },
      (error, stdout, stderr) => {
        if (error) {
          reject(new Error((stderr || error.message || "").toString().slice(0, 500)));
          return;
        }
        const lines = (stdout || "").split("\n").map((l) => l.trim()).filter(Boolean);
        resolve(lines[0] || "");
      });
    child.stdin.end(JSON.stringify({ ...spec, output: outputBase }), "utf-8");
  });
}

async function office(req, res) {
  let payload;
  try {
    payload = JSON.parse(await readBody(req, MAX_DECK_BODY_BYTES) || "{}");
  } catch (e) {
    return fail(res, 400, "Corps illisible : " + e.message);
  }
  const spec = payload.spec;
  if (!spec || typeof spec !== "object" || Array.isArray(spec)) {
    return fail(res, 400, "La description du document est manquante (champ « spec »).");
  }
  // Le format est décidé ICI, pas dans la description : un fichier dont le nom et le type sortent
  // d'un champ libre du modèle finirait par mentir sur son contenu.
  const format = typeof payload.format === "string" ? payload.format.trim().toLowerCase() : "";
  if (!OFFICE_FORMATS[format]) {
    return fail(res, 400, "Format inconnu « " + format + " ». Formats connus : "
      + Object.keys(OFFICE_FORMATS).join(", ") + ".");
  }
  const dir = await mkdtemp(path.join(tmpdir(), "cg-office-"));
  try {
    const produced = await buildOffice({ ...spec, format }, path.join(dir, "document"));
    const file = await readFile(produced || path.join(dir, "document." + format));
    if (file.length > MAX_OFFICE_BYTES) {
      return fail(res, 413, "Document trop lourd : " + file.length + " octets.");
    }
    res.writeHead(200, { "Content-Type": OFFICE_FORMATS[format], "Content-Length": file.length });
    res.end(file);
  } catch (e) {
    return fail(res, 422, (e.message || "Le document n'a pas pu etre construit.").slice(0, 500));
  } finally {
    await rm(dir, { recursive: true, force: true }).catch(() => undefined);
  }
}

/**
 * La branche « draw.io » (F-142 / SF-142-13) : une description entre, DEUX artefacts sortent — le
 * `.drawio` réouvrable ET son aperçu PNG.
 *
 * Le XML et le SVG viennent du module PUR `drawio.js` ; seule la rasterisation a lieu ici, avec le
 * chromium que le service a déjà. La réponse est du JSON : un corps binaire ne peut pas porter deux
 * fichiers, et c'est le `.drawio` — pas l'image — qui a de la valeur.
 */
async function rasterize(svg, width, height, dir) {
  const page = path.join(dir, "apercu.svg");
  const output = path.join(dir, "apercu.png");
  await writeFile(page, svg, "utf-8");
  // Un facteur d'échelle 2 tant que l'image reste raisonnable : un aperçu flou dans une slide est
  // exactement le défaut que SF-142-06 a corrigé pour Mermaid.
  const scale = width * height <= DRAWIO_SCALE_BUDGET ? 2 : 1;
  const args = ["--headless=new", "--no-sandbox", "--disable-gpu", "--disable-dev-shm-usage",
    "--hide-scrollbars", `--force-device-scale-factor=${scale}`, "--virtual-time-budget=3000",
    `--user-data-dir=${path.join(dir, "profil")}`, `--window-size=${width},${height}`,
    `--screenshot=${output}`, "file://" + page];
  await new Promise((resolve, reject) => {
    execFile(CHROME, args, { timeout: PREVIEW_TIMEOUT_MS }, (error, stdout, stderr) => {
      if (error) {
        reject(new Error((stderr || error.message || "").toString().slice(0, 300)));
        return;
      }
      resolve();
    });
  });
  return readFile(output);
}

async function renderDrawioRequest(payload, res) {
  let built;
  try {
    built = drawio.build(payload.spec);
  } catch (e) {
    // Description refusée : la raison dit quoi corriger. Rien n'est fabriqué.
    return fail(res, 422, (e.message || "Le schéma n'a pas pu être construit.").slice(0, 500));
  }
  const xml = Buffer.from(built.xml, "utf-8");
  if (xml.length > MAX_IMAGE_BYTES) {
    return fail(res, 413, "Fichier draw.io trop lourd : " + xml.length + " octets.");
  }
  const dir = await mkdtemp(path.join(tmpdir(), "cg-drawio-"));
  // L'aperçu ne fait JAMAIS échouer le `.drawio` : c'est l'éditable qui a de la valeur, l'image se
  // refait. L'échec est DIT, il n'est pas tu.
  let png = "";
  let previewError = "";
  try {
    const image = await rasterize(built.svg, built.width, built.height, dir);
    if (image.length > MAX_IMAGE_BYTES) {
      previewError = "Aperçu trop lourd (" + image.length + " octets) : le fichier draw.io est complet.";
    } else {
      png = image.toString("base64");
    }
  } catch (e) {
    previewError = (e.message || "rendu d'aperçu en échec").slice(0, 300);
  } finally {
    await rm(dir, { recursive: true, force: true }).catch(() => undefined);
  }
  const body = JSON.stringify({
    drawio: xml.toString("base64"),
    png,
    previewError,
    width: built.width,
    height: built.height,
  });
  send(res, 200, body, "application/json; charset=utf-8");
}

/** La branche « icônes officielles » : une description entre, un PNG sort. */
async function renderCloudRequest(payload, res) {
  const spec = payload.spec;
  if (!spec || typeof spec !== "object" || Array.isArray(spec)) {
    return fail(res, 400, "La description du schema est manquante (champ « spec »).");
  }
  const dir = await mkdtemp(path.join(tmpdir(), "cg-cloud-"));
  try {
    const base = path.join(dir, "cloud");
    const produced = await renderCloud(spec, base);
    const image = await readFile(produced.file || base + ".png");
    if (image.length > MAX_IMAGE_BYTES) {
      return fail(res, 413, "Image rendue trop lourde : " + image.length + " octets.");
    }
    const headers = { "Content-Type": "image/png", "Content-Length": image.length };
    if (produced.unknown) {
      // L'avertissement voyage avec l'image : l'agent doit pouvoir DIRE lesquels n'avaient pas d'icône.
      headers["X-Cg-Unknown-Types"] = produced.unknown;
    }
    if (produced.notice) {
      // F-142 / SF-142-17 : « ce schéma est trop dense » se dit, il ne se devine pas.
      headers["X-Cg-Diagram-Notice"] = produced.notice;
    }
    res.writeHead(200, headers);
    res.end(image);
  } catch (e) {
    // Le message vient du generateur : il dit quoi corriger (type inconnu, lien pendant, borne).
    return fail(res, 422, (e.message || "Le schema n'a pas pu etre rendu.").slice(0, 500));
  } finally {
    await rm(dir, { recursive: true, force: true }).catch(() => undefined);
  }
}

const server = http.createServer((req, res) => {
  if (req.method === "GET" && req.url === "/health") {
    return send(res, 200, JSON.stringify({ status: "UP" }), "application/json; charset=utf-8");
  }
  if (req.method === "POST" && req.url === "/presentation") {
    return presentation(req, res).catch((e) => fail(res, 500, "Erreur interne : " + e.message));
  }
  if (req.method === "POST" && req.url === "/document") {
    return office(req, res).catch((e) => fail(res, 500, "Erreur interne : " + e.message));
  }
  if (req.method === "POST" && req.url === "/render") {
    return render(req, res).catch((e) => fail(res, 500, "Erreur interne : " + e.message));
  }
  return fail(res, 404, "Rien ici.");
});

server.requestTimeout = DECK_TIMEOUT_MS + 10000;
server.listen(PORT, () => console.log("diagram-renderer a l'ecoute sur " + PORT));
