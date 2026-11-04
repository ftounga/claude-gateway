import { ComponentFixture, TestBed } from '@angular/core/testing';

import { MailCard, briefCard, mailPieceId } from './jarvis-mail';
import { JarvisMailCardComponent } from './jarvis-mail-card.component';

function card(over: Partial<MailCard> = {}): MailCard {
  return { mode: 'capture', id: 'i1', from: 'devis@helios.example', fromName: 'Helios', subject: 'Le devis',
    receivedAt: '2026-11-03T08:00:00Z', account: 'Pro', text: 'Bonjour', truncated: false, ...over };
}

describe('jarvis-mail (courriel à l’écran, F-242 / SF-242-04)', () => {
  it('mailPieceId ne retient qu’un identifiant courriel:<uuid> bien formé', () => {
    expect(mailPieceId(['proof:x', 'courriel:11111111-1111-1111-1111-111111111111']))
      .toBe('11111111-1111-1111-1111-111111111111');
    expect(mailPieceId(['courriel:../../etc'])).toBeNull();
    expect(mailPieceId(['mail:abc'])).toBeNull();
    expect(mailPieceId(null)).toBeNull();
  });

  it('briefCard : rien sans texte ; sinon le premier courriel mis en avant', () => {
    expect(briefCard({ text: '', items: [] })).toBeNull();
    const c = briefCard({ text: 'Un mail à traiter : Helios.', items: [{ id: 'a', from: 'x@x', fromName: 'Helios',
      subject: 'Devis', reason: 'Helios demande le devis' }] });
    expect(c?.mode).toBe('highlight');
    expect(c?.text).toBe('Helios demande le devis');
  });
});

describe('JarvisMailCardComponent (F-242 / SF-242-04)', () => {
  let fixture: ComponentFixture<JarvisMailCardComponent>;

  function mount(c: MailCard | null): HTMLElement {
    fixture = TestBed.createComponent(JarvisMailCardComponent);
    fixture.componentRef.setInput('card', c);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  beforeEach(() => TestBed.configureTestingModule({ imports: [JarvisMailCardComponent] }));

  it('en-têtes, « Contenu externe », corps', () => {
    const el = mount(card());
    expect(el.querySelector('.jmc__from')?.textContent).toContain('Helios <devis@helios.example>');
    expect(el.querySelector('.jmc__subject')?.textContent).toContain('Le devis');
    expect(el.querySelector('.jmc__external')?.textContent).toContain('Contenu externe');
    expect(el.querySelector('.jmc__body')?.textContent).toContain('Bonjour');
  });

  it('le corps reste du TEXTE : aucune image ni script créés, même si le texte en contient', () => {
    const el = mount(card({ text: '<img src="https://espion.example/p.gif"><script>alert(1)</script> Bonjour' }));
    expect(el.querySelector('img')).toBeNull();
    expect(el.querySelector('script')).toBeNull();
    expect(el.querySelector('.jmc__body')?.textContent).toContain('<img');
  });

  it('coupe annoncée ; brouillon sans mention externe ; rien sans carte', () => {
    let el = mount(card({ truncated: true }));
    expect(el.querySelector('.jmc__cut')).not.toBeNull();
    el = mount(card({ mode: 'draft', to: 'paul@x.example' }));
    expect(el.querySelector('.jmc__external')).toBeNull();
    expect(el.querySelector('.jmc__from')?.textContent).toContain('paul@x.example');
    el = mount(null);
    expect(el.querySelector('.jmc')).toBeNull();
  });
});
