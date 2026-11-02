// Prototype JETABLE de l essai F-238 itération 0 (2026-11-02) — pas du code applicatif. Lancé avec elkjs 0.9.3 et les icônes de diagrams 0.24.4.
// Prototype F-238 itération 0 : spec cloud -> ELK layered orthogonal -> SVG aux conventions AWS.
// node render.js spec.json out.svg [DIR]
const fs = require("fs");
const path = require("path");
const ELK = require("elkjs/lib/elk.bundled.js");
const elk = new ELK();

const TIER = { "aws.users": 0, "onprem.users": 0, "aws.route53": 0, "aws.cloudfront": 0, "aws.igw": 1, "aws.alb": 1, "aws.nlb": 1, "aws.apigw": 1, "aws.natgateway": 3, "aws.ec2": 2, "aws.lambda": 2, "aws.eks": 2, "aws.rds": 3, "aws.s3": 3, "aws.ddb": 3 };
const ICONS = {
  "aws.users": "users", "onprem.users": "onprem-users", "aws.route53": "route-53", "aws.alb": "elb-application-load-balancer",
  "aws.nlb": "elb-network-load-balancer", "aws.natgateway": "nat-gateway", "aws.ec2": "ec2", "aws.rds": "rds",
  "aws.s3": "simple-storage-service-s3", "aws.ecr": "ecr", "aws.igw": "internet-gateway", "aws.eks": "elastic-kubernetes-service",
  "aws.cloudwatch": "cloudwatch", "aws.sm": "secrets-manager", "aws.cloudfront": "cloudfront", "aws.apigw": "api-gateway",
  "aws.cognito": "cognito", "aws.lambda": "lambda", "aws.ddb": "dynamodb", "aws.sqs": "simple-queue-service-sqs",
  "aws.sns": "simple-notification-service-sns",
};
// Conventions des groupes AWS (Architecture Icons, « Groups ») : bordure, trait, fond, icône.
const GROUP_STYLE = {
  cloud:   { stroke: "#232F3E", dash: "", fill: "none", icon: "aws", text: "#232F3E" },
  region:  { stroke: "#00A4A6", dash: "6 4", fill: "none", icon: null, flag: true, text: "#00A4A6" },
  vpc:     { stroke: "#8C4FFF", dash: "", fill: "none", icon: "vpc", text: "#8C4FFF" },
  az:      { stroke: "#00A4A6", dash: "6 4", fill: "none", icon: null, text: "#00A4A6" },
  public:  { stroke: "#7AA116", dash: "", fill: "#F2F6E8", icon: "public-subnet", text: "#248814" },
  private: { stroke: "#00A4A6", dash: "", fill: "#E6F6F7", icon: "private-subnet", text: "#147EBA" },
  asg:     { stroke: "#ED7100", dash: "6 4", fill: "none", icon: null, text: "#ED7100" },
  generic: { stroke: "#7D8998", dash: "6 4", fill: "none", icon: null, text: "#5A6B86" },
};
function kindOf(g) {
  if (g.kind) return g.kind;
  const l = (g.label || g.id).toLowerCase();
  if (l.includes("aws cloud") || l.includes("compte")) return "cloud";
  if (l.startsWith("région") || l.startsWith("region")) return "region";
  if (l.startsWith("vpc")) return "vpc";
  if (l.includes("availability zone") || l.startsWith("az ")) return "az";
  if (l.includes("public")) return "public";
  if (l.includes("privé") || l.includes("private")) return "private";
  if (l.includes("auto scaling")) return "asg";
  return "generic";
}
const esc = (s) => String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;");
const textW = (s, px) => Math.ceil(String(s).length * px * 0.58) + 8;
const icon = (name) => {
  const f = path.join(__dirname, "icons", name + ".png");
  return fs.existsSync(f) ? "data:image/png;base64," + fs.readFileSync(f).toString("base64") : null;
};

async function main() {
  const spec = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
  const dir = process.argv[4] || (spec.direction === "TB" ? "DOWN" : "RIGHT");
  const groups = new Map((spec.groups || []).map((g) => [g.id, { ...g, kind: kindOf(g), children: [] }]));
  const root = { id: "root", children: [], edges: [], layoutOptions: {
    "elk.algorithm": "layered", ...(process.env.TIERS ? { "elk.partitioning.activate": "true" } : {}), "elk.direction": dir, "elk.edgeRouting": "ORTHOGONAL",
    "elk.hierarchyHandling": "INCLUDE_CHILDREN", 
    "elk.spacing.nodeNode": "44", "elk.layered.spacing.nodeNodeBetweenLayers": "56",
    "elk.spacing.edgeNode": "18", "elk.spacing.edgeEdge": "14", "elk.layered.spacing.edgeNodeBetweenLayers": "20",
    "elk.layered.considerModelOrder.strategy": "NODES_AND_EDGES",
    "elk.layered.nodePlacement.strategy": "NETWORK_SIMPLEX",
    "elk.layered.crossingMinimization.forceNodeModelOrder": "false",
    "elk.edgeLabels.inline": "false", "elk.padding": "[top=24,left=24,bottom=24,right=24]",
  } };
  const elkNodes = new Map();
  for (const g of groups.values()) {
    const n = { id: g.id, children: [], layoutOptions: { "elk.padding": "[top=48,left=20,bottom=20,right=20]", "elk.nodeSize.minimum": `(${textW(g.label, 13) + 60}, 60)`, "elk.nodeSize.constraints": "[MINIMUM_SIZE]" } };
    elkNodes.set(g.id, n);
  }
  for (const g of groups.values()) (g.parent && elkNodes.get(g.parent) ? elkNodes.get(g.parent).children : root.children).push(elkNodes.get(g.id));
  const meta = new Map();
  for (const nd of spec.nodes) {
    const w = Math.max(96, textW(nd.label, 13)); const n = { id: nd.id, width: w, height: 96 };
    if (process.env.TIERS) n.layoutOptions = { "elk.partitioning.partition": String(TIER[nd.type] ?? 2) };
    meta.set(nd.id, nd);
    (nd.group && elkNodes.get(nd.group) ? elkNodes.get(nd.group).children : root.children).push(n);
  }
  // Ordre des groupes = ordre déclaré (sinon les nœuds passent devant).
  // Chaque lien est déclaré dans le plus proche ancêtre commun (LCA) de ses extrémités.
  const parentOf = new Map();
  for (const g of groups.values()) parentOf.set(g.id, g.parent && groups.has(g.parent) ? g.parent : "root");
  for (const nd of spec.nodes) parentOf.set(nd.id, nd.group && groups.has(nd.group) ? nd.group : "root");
  const chain = (id) => { const c = []; let x = parentOf.get(id); while (x) { c.push(x); x = x === "root" ? null : parentOf.get(x); } return c; };
  const lca = (a, b) => { const cb = new Set(chain(b)); return chain(a).find((x) => cb.has(x)) || "root"; };
  spec.edges.forEach((e, i) => { const host = lca(e.from, e.to); const h = host === "root" ? root : elkNodes.get(host); (h.edges = h.edges || []).push({ id: "e" + i, sources: [e.from], targets: [e.to],
    labels: e.label ? [{ text: e.label, width: textW(e.label, 12), height: 16 }] : [] }); });
  const out = await elk.layout(root);

  const parts = [];
  const W = out.width + 40, H = out.height + 90;
  parts.push(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" width="${W}" height="${H}" font-family="Arial, Helvetica, sans-serif">`);
  parts.push(`<defs><marker id="a" viewBox="0 0 10 10" refX="10" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="#545B64"/></marker></defs>`);
  parts.push(`<rect width="100%" height="100%" fill="#fff"/><text x="20" y="32" font-size="20" font-weight="bold" fill="#232F3E">${esc(spec.title || "")}</text><g transform="translate(20,50)">`);
  const walk = (n, depth, ox = 0, oy = 0) => {
    for (const c0 of n.children || []) {
      const c = { ...c0, x: c0.x + ox, y: c0.y + oy };
      if (groups.has(c.id)) {
        const g = groups.get(c.id); const s = GROUP_STYLE[g.kind];
        parts.push(`<rect x="${c.x}" y="${c.y}" width="${c.width}" height="${c.height}" fill="${s.fill}" stroke="${s.stroke}" stroke-width="1.6" ${s.dash ? `stroke-dasharray="${s.dash}"` : ""}/>`);
        let tx = c.x + 10;
        const ic = s.icon && icon(s.icon);
        if (ic) { parts.push(`<rect x="${c.x}" y="${c.y}" width="30" height="30" fill="${s.stroke}"/><image href="${ic}" x="${c.x + 1}" y="${c.y + 1}" width="28" height="28"/>`); tx = c.x + 38; }
        else if (s.flag) { parts.push(`<rect x="${c.x}" y="${c.y}" width="30" height="30" fill="${s.stroke}"/><path d="M${c.x + 9},${c.y + 7} v17 M${c.x + 9},${c.y + 8} h12 l-3,4 l3,4 h-12" stroke="#fff" stroke-width="1.8" fill="none"/>`); tx = c.x + 38; }
        parts.push(`<text x="${tx}" y="${c.y + 20}" font-size="13" fill="${s.text}">${esc(g.label)}</text>`);
        walk(c0, depth + 1, c.x, c.y);
      } else {
        const m = meta.get(c.id); const ic = icon(ICONS[m.type] || "general");
        const cx = c.x + c.width / 2;
        parts.push(`<image href="${ic}" x="${cx - 32}" y="${c.y}" width="64" height="64"/>`);
        parts.push(`<text x="${cx}" y="${c.y + 84}" font-size="13" text-anchor="middle" fill="#232F3E">${esc(m.label)}</text>`);
      }
    }
  };
  walk(out, 0);
  const abs = new Map(); const edgesAll = [];
  const collect = (n, ox, oy) => { for (const e of n.edges || []) edgesAll.push([e, ox, oy]); for (const c of n.children || []) { abs.set(c.id, [c.x + ox, c.y + oy]); collect(c, c.x + ox, c.y + oy); } };
  collect(out, 0, 0);
  const segs = [];
  for (const [e, ox0, oy0] of edgesAll) {
    // Repère : celui du conteneur déclaré (elkjs 0.9 : coordonnées relatives au conteneur du lien).
    const ox = ox0, oy = oy0;
    for (const s of e.sections || []) {
      const P = [s.startPoint, ...(s.bendPoints || []), s.endPoint].map((p) => ({ x: p.x + ox, y: p.y + oy }));
      for (let i = 1; i < P.length; i++) segs.push([P[i - 1], P[i]]);
      parts.push(`<polyline class="lien" points="${P.map((p) => p.x + "," + p.y).join(" ")}" fill="none" stroke="#545B64" stroke-width="1.6" marker-end="url(#a)"/>`);
    }
    for (const l of e.labels || []) {
      const lx = l.x + ox, ly = l.y + oy;
      parts.push(`<rect x="${lx - 2}" y="${ly}" width="${l.width + 4}" height="${l.height}" fill="#fff" opacity=".9"/><text x="${lx + l.width / 2}" y="${ly + 12}" font-size="12" text-anchor="middle" fill="#545B64">${esc(l.text)}</text>`);
    }
  }
  const oblique = segs.filter(([a, b]) => Math.abs(a.x - b.x) > 0.5 && Math.abs(a.y - b.y) > 0.5).length;
  console.log(process.argv[3], "segments", segs.length, "obliques", oblique);
  parts.push("</g></svg>");
  fs.writeFileSync(process.argv[3], parts.join("\n"));
}
main().catch((e) => { console.error(e); process.exit(1); });
