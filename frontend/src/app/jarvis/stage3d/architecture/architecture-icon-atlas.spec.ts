import {
  ATLAS_CELL, ATLAS_MAX_ICONS, AtlasNode, CELL_NEUTRAL, CELL_UNKNOWN, IconImage, composeIconAtlas,
  httpIconFetcher, iconUrl, loadIconAtlas, planIconAtlas, validIconName,
} from './architecture-icon-atlas';

/** F-238 / SF-238-08 — l'atlas des icônes : une image, des cellules sûres, jamais une icône approchante. */
describe('architecture-icon-atlas (SF-238-08)', () => {
  const node = (resolved: string | null, unknown = false): AtlasNode => ({ resolved, unknown });

  function square(color: string, size = 64): HTMLCanvasElement {
    const c = document.createElement('canvas');
    c.width = size;
    c.height = size;
    const ctx = c.getContext('2d')!;
    ctx.fillStyle = color;
    ctx.fillRect(0, 0, size, size);
    return c;
  }

  function pixelAtCell(canvas: HTMLCanvasElement, cell: number, columns: number): number[] {
    const x = (cell % columns) * ATLAS_CELL + ATLAS_CELL / 2;
    const y = Math.floor(cell / columns) * ATLAS_CELL + ATLAS_CELL / 2;
    return Array.from(canvas.getContext('2d')!.getImageData(x, y, 1, 1).data);
  }

  it('noms : motif de icons.js, fournisseurs permis, aucune traversée', () => {
    for (const ok of ['aws/database/rds', 'onprem/client/users', 'aws/network/elb-application-load-balancer']) {
      expect(validIconName(ok)).withContext(ok).toBeTrue();
    }
    for (const bad of ['', 'aws', 'AWS/x/y', 'aws/../x', 'aws/./x', 'evil/x/y', 'aws/a/b/c/d', 'aws/%2e%2e/x',
      'aws\\x\\y', null, undefined]) {
      expect(validIconName(bad as string)).withContext(String(bad)).toBeFalse();
    }
    expect(iconUrl('aws/database/rds')).toBe('/api/diagrams/icons/aws/database/rds.png');
    expect(iconUrl('aws/../etc/passwd')).toBeNull();
    expect(iconUrl(null)).toBeNull();
  });

  it('plan : icônes distinctes, triées (déterministe), inconnus et chemins invalides exclus', () => {
    const nodes = [node('aws/database/rds'), node('aws/compute/ec2'), node('aws/database/rds'),
      node('aws/x/y', true), node(null), node('../x')];
    const plan = planIconAtlas(nodes);
    expect(plan.icons).toEqual(['aws/compute/ec2', 'aws/database/rds']);
    expect(plan.dropped).toEqual([]);
    expect(plan.columns).toBe(2);
    expect(plan.size).toBe(256);
    expect(planIconAtlas([...nodes].reverse())).toEqual(plan);
  });

  it('60 icônes distinctes ⇒ une seule image 1024 × 1024, cellules de 128 px', () => {
    const nodes = Array.from({ length: 60 }, (_, i) => node(`aws/svc/icon-${String(i).padStart(2, '0')}`));
    const plan = planIconAtlas(nodes);
    expect(plan.icons.length).toBe(60);
    expect(plan.columns).toBe(8);
    expect(plan.rows).toBe(8);
    expect(plan.size).toBe(1024);
    expect(plan.size / plan.columns).toBe(ATLAS_CELL);
  });

  it('au-delà de la borne : les icônes en trop restent sur la boîte neutre (jamais une autre)', () => {
    const nodes = Array.from({ length: ATLAS_MAX_ICONS + 3 }, (_, i) => node(`aws/svc/i${String(i).padStart(3, '0')}`));
    const plan = planIconAtlas(nodes);
    expect(plan.icons.length).toBe(ATLAS_MAX_ICONS);
    expect(plan.dropped.length).toBe(3);
    expect(plan.size).toBe(1024);
    const images = new Map(plan.icons.map((n) => [n, square('#ff0000', 8) as IconImage] as const));
    const atlas = composeIconAtlas(plan, images);
    expect(atlas.cellOf(node(plan.dropped[0]))).toBe(CELL_NEUTRAL);
    expect(atlas.missing).toEqual(plan.dropped);
  });

  it('composition : icône dessinée NON recolorée ; icône absente ⇒ cellule vide et nœud sur la boîte neutre', () => {
    const plan = planIconAtlas([node('aws/a/present'), node('aws/a/zabsent')]);
    const atlas = composeIconAtlas(plan, new Map([['aws/a/present', square('#ff0000') as IconImage],
      ['aws/a/zabsent', null]]));

    expect(atlas.image.width).toBe(plan.size);
    const present = atlas.cellOf(node('aws/a/present'));
    expect(present).toBe(2);
    expect(pixelAtCell(atlas.image, present, atlas.columns)).toEqual([255, 0, 0, 255]);
    // La cellule prévue pour l'absente (3) n'est PAS dessinée : aucune autre icône à sa place.
    expect(pixelAtCell(atlas.image, 3, atlas.columns)[3]).toBe(0);
    expect(atlas.cellOf(node('aws/a/zabsent'))).toBe(CELL_NEUTRAL);
    expect(atlas.cellOf(node('aws/a/present', true))).toBe(CELL_UNKNOWN);
    expect(atlas.cellOf(node(null))).toBe(CELL_NEUTRAL);
    expect(atlas.missing).toEqual(['aws/a/zabsent']);
    // Les deux boîtes réservées sont dessinées (neutre et pointillé).
    expect(pixelAtCell(atlas.image, CELL_NEUTRAL, atlas.columns)[3]).toBeGreaterThan(0);
    expect(pixelAtCell(atlas.image, CELL_UNKNOWN, atlas.columns)[3]).toBeGreaterThan(0);
  });

  it('une image vide (0 × 0) compte comme absente', () => {
    const plan = planIconAtlas([node('aws/a/b')]);
    const atlas = composeIconAtlas(plan, new Map([['aws/a/b', square('#00ff00', 0) as IconImage]]));
    expect(atlas.cellOf(node('aws/a/b'))).toBe(CELL_NEUTRAL);
  });

  it('uv : convention flipY de CanvasTexture (v = 1 en haut)', () => {
    const atlas = composeIconAtlas(planIconAtlas([node('aws/a/b'), node('aws/a/c'), node('aws/a/d')]), new Map());
    expect(atlas.columns).toBe(4);
    expect(atlas.uv(0)).toEqual({ u0: 0, u1: 0.25, v0: 0.75, v1: 1 });
    expect(atlas.uv(5)).toEqual({ u0: 0.25, u1: 0.5, v0: 0.5, v1: 0.75 });
  });

  it('chargement : une requête par icône distincte, une erreur vaut une icône absente', async () => {
    const calls: string[] = [];
    const fetcher = async (name: string): Promise<IconImage | null> => {
      calls.push(name);
      if (name === 'aws/a/boom') {
        throw new Error('réseau');
      }
      return name === 'aws/a/none' ? null : square('#0000ff') as IconImage;
    };
    const atlas = await loadIconAtlas([node('aws/a/ok'), node('aws/a/ok'), node('aws/a/none'), node('aws/a/boom')],
      fetcher);
    expect(calls.sort()).toEqual(['aws/a/boom', 'aws/a/none', 'aws/a/ok']);
    expect(atlas.cellOf(node('aws/a/ok'))).toBeGreaterThanOrEqual(2);
    expect(atlas.cellOf(node('aws/a/none'))).toBe(CELL_NEUTRAL);
    expect(atlas.cellOf(node('aws/a/boom'))).toBe(CELL_NEUTRAL);
    expect(atlas.totalMs).toBeGreaterThanOrEqual(atlas.composeMs);
  });

  it('chargeur HTTP : URL du proxy, en-têtes JWT, 404 ⇒ null, nom non sûr ⇒ aucune requête', async () => {
    const seen: { url: string; auth: string | null }[] = [];
    const fakeFetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
      seen.push({ url: String(input), auth: new Headers(init?.headers).get('Authorization') });
      return String(input).includes('nope') ? new Response(null, { status: 404 })
        : new Response(new Blob(['png']), { status: 200 });
    }) as typeof fetch;
    const decoded = square('#123456') as IconImage;
    const fetcher = httpIconFetcher(() => ({ Authorization: 'Bearer t' }), fakeFetch, async () => decoded);

    expect(await fetcher('aws/database/rds')).toBe(decoded);
    expect(await fetcher('aws/database/nope')).toBeNull();
    expect(await fetcher('aws/../secret')).toBeNull();
    expect(seen).toEqual([
      { url: '/api/diagrams/icons/aws/database/rds.png', auth: 'Bearer t' },
      { url: '/api/diagrams/icons/aws/database/nope.png', auth: 'Bearer t' },
    ]);
  });

  it('chargeur HTTP : image illisible ⇒ null (jamais une exception)', async () => {
    const fakeFetch = (async () => new Response(new Blob(['x']), { status: 200 })) as typeof fetch;
    const fetcher = httpIconFetcher(() => ({}), fakeFetch, async () => {
      throw new Error('illisible');
    });
    expect(await fetcher('aws/a/b')).toBeNull();
  });

  it('performance : 60 icônes (256 px) composées en ≤ 300 ms', () => {
    const nodes = Array.from({ length: 60 }, (_, i) => node(`aws/svc/icon-${String(i).padStart(2, '0')}`));
    const plan = planIconAtlas(nodes);
    const images = new Map(plan.icons.map((n, i) => [n, square(`hsl(${i * 6}, 80%, 50%)`, 256) as IconImage] as const));
    const atlas = composeIconAtlas(plan, images);
    expect(atlas.missing).toEqual([]);
    expect(atlas.composeMs).toBeLessThanOrEqual(300);
  });
});
