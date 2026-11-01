import { FrameLoop } from './jarvis-frame-loop';
import { FakeFrameHost } from './jarvis-stage3d.testing';

describe('F-236 / SF-236-01 — boucle d’images (§6.2)', () => {
  it('dessine tant que l’onglet est visible', () => {
    const host = new FakeFrameHost();
    const draw = jasmine.createSpy('draw');
    const loop = new FrameLoop(host, draw);
    loop.start();
    host.step(5);
    expect(draw).toHaveBeenCalledTimes(5);
    expect(loop.frames).toBe(5);
  });

  it('onglet caché ⇒ zéro image ; reprise au retour', () => {
    const host = new FakeFrameHost();
    const draw = jasmine.createSpy('draw');
    const loop = new FrameLoop(host, draw);
    loop.start();
    host.step(2);
    host.setHidden(true);
    expect(host.pending.size).toBe(0);
    host.step(10);
    expect(draw).toHaveBeenCalledTimes(2);
    host.setHidden(false);
    host.step(3);
    expect(draw).toHaveBeenCalledTimes(5);
  });

  it('ouvert onglet caché ⇒ aucune image demandée', () => {
    const host = new FakeFrameHost();
    host.isHidden = true;
    const loop = new FrameLoop(host, jasmine.createSpy('draw'));
    loop.start();
    expect(loop.active).toBeFalse();
    expect(host.pending.size).toBe(0);
  });

  it('pause puis reprise (perte de contexte), arrêt définitif sans écouteur résiduel', () => {
    const host = new FakeFrameHost();
    const draw = jasmine.createSpy('draw');
    const loop = new FrameLoop(host, draw);
    loop.start();
    loop.pause();
    host.step(3);
    expect(draw).not.toHaveBeenCalled();
    loop.start();
    host.step(1);
    expect(draw).toHaveBeenCalledTimes(1);
    loop.stop();
    expect(host.listenerCount).toBe(0);
    loop.start();
    host.setHidden(false);
    host.step(3);
    expect(draw).toHaveBeenCalledTimes(1);
  });
});
