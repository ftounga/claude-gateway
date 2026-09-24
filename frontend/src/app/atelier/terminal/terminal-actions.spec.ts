import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { AtelierTerminalComponent } from './atelier-terminal.component';
import { TerminalActionsPanelComponent, ageLabel } from './terminal-actions-panel.component';
import { TerminalAction, TerminalActionElsewhere } from '../../core/models/terminal-actions.models';
import { TerminalActionsService } from '../../core/services/terminal-actions.service';

function action(id: string, description: string, over: Partial<TerminalAction> = {}): TerminalAction {
  return {
    id, workspaceId: 'w-1', subjectId: null, description, blocks: null, person: null,
    kind: 'ACTION', status: 'OPEN', closedReason: null, closedAt: null,
    createdAt: '2026-09-20T08:00:00Z', ...over,
  };
}

/**
 * **Le menu des actions à faire** (F-154 / SF-154-03) : la pastille absente à zéro, la liste, les
 * gestes réversibles, et un écran qui ne ment jamais quand un appel échoue.
 */
describe('Les actions à faire du terminal (F-154 / SF-154-03)', () => {

  describe('la pastille dans la barre', () => {
    let fixture: ComponentFixture<AtelierTerminalComponent>;
    let service: jasmine.SpyObj<TerminalActionsService>;

    function build(actions: TerminalAction[]): void {
      service = jasmine.createSpyObj<TerminalActionsService>('TerminalActionsService',
        ['list', 'elsewhere', 'close', 'cancel', 'reopen']);
      service.list.and.returnValue(of(actions));
      service.elsewhere.and.returnValue(of([]));
      TestBed.configureTestingModule({
        imports: [AtelierTerminalComponent, NoopAnimationsModule],
        providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
          { provide: TerminalActionsService, useValue: service }],
      });
      fixture = TestBed.createComponent(AtelierTerminalComponent);
      fixture.componentInstance.projectName = 'mon-projet';
      fixture.componentInstance.projectId = 'w-1';
      fixture.detectChanges();
    }

    afterEach(() => TestBed.resetTestingModule());

    it("n'affiche RIEN quand il n'y a rien à faire — une pastille à zéro est un bruit permanent", () => {
      build([]);
      expect(fixture.componentInstance.pendingActions()).toBe(0);
      expect(fixture.nativeElement.querySelector('.terminal-todo')).toBeNull();
    });

    it('annonce le nombre restant, et ouvre le panneau au clic', () => {
      build([action('a-1', 'Demander l’accès VPN'), action('a-2', 'Relancer le support')]);

      const chip: HTMLButtonElement = fixture.nativeElement.querySelector('.terminal-todo');
      expect(chip).not.toBeNull();
      expect(chip.textContent).toContain('2 à faire');

      chip.click();
      fixture.detectChanges();
      expect(fixture.componentInstance.actionsOpen()).toBeTrue();
      expect(fixture.nativeElement.querySelector('app-terminal-actions-panel')).not.toBeNull();
    });

    it("laisse la pastille à zéro si le chargement échoue — mieux vaut rien qu'un chiffre faux", () => {
      service = jasmine.createSpyObj<TerminalActionsService>('TerminalActionsService',
        ['list', 'elsewhere', 'close', 'cancel', 'reopen']);
      service.list.and.returnValue(throwError(() => new Error('réseau')));
      service.elsewhere.and.returnValue(of([]));
      TestBed.configureTestingModule({
        imports: [AtelierTerminalComponent, NoopAnimationsModule],
        providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
          { provide: TerminalActionsService, useValue: service }],
      });
      fixture = TestBed.createComponent(AtelierTerminalComponent);
      fixture.componentInstance.projectId = 'w-1';
      fixture.detectChanges();

      expect(fixture.componentInstance.pendingActions()).toBe(0);
      expect(fixture.nativeElement.querySelector('.terminal-todo')).toBeNull();
    });
  });

  describe('le panneau', () => {
    let fixture: ComponentFixture<TerminalActionsPanelComponent>;
    let component: TerminalActionsPanelComponent;
    let service: jasmine.SpyObj<TerminalActionsService>;
    let snackBar: jasmine.SpyObj<MatSnackBar>;

    function build(actions: TerminalAction[], elsewhere: TerminalActionElsewhere[] = []): void {
      service = jasmine.createSpyObj<TerminalActionsService>('TerminalActionsService',
        ['list', 'elsewhere', 'close', 'cancel', 'reopen']);
      service.list.and.returnValue(of(actions));
      service.elsewhere.and.returnValue(of(elsewhere));
      snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
      TestBed.configureTestingModule({
        imports: [TerminalActionsPanelComponent, NoopAnimationsModule],
        providers: [provideHttpClient(), provideHttpClientTesting(),
          { provide: TerminalActionsService, useValue: service },
          { provide: MatSnackBar, useValue: snackBar }],
      });
      fixture = TestBed.createComponent(TerminalActionsPanelComponent);
      fixture.componentRef.setInput('workspaceId', 'w-1');
      component = fixture.componentInstance;
      fixture.detectChanges();
    }

    afterEach(() => TestBed.resetTestingModule());

    it('liste les actions avec ce qu’elles débloquent, qui est concerné et leur âge', () => {
      build([action('a-1', 'Demander l’accès VPN à Karim',
        { blocks: 'le déploiement du connecteur', person: 'Karim' })]);

      const text = fixture.nativeElement.textContent;
      expect(text).toContain('Demander l’accès VPN à Karim');
      expect(text).toContain('bloque : le déploiement du connecteur');
      expect(text).toContain('Karim');
    });

    it('« C’est fait » sort l’action de la liste et propose Rétablir ; Rétablir la remet', () => {
      const a = action('a-1', 'Demander l’accès VPN');
      build([a]);
      service.close.and.returnValue(of({ ...a, status: 'DONE' }));
      service.reopen.and.returnValue(of(a));

      component.markDone(a);
      fixture.detectChanges();
      expect(component.actions().length).toBe(0);
      expect(component.closedRecently().length).toBe(1);
      expect(fixture.nativeElement.textContent).toContain('Rétablir');

      component.reopen(a);
      fixture.detectChanges();
      expect(component.actions().length).toBe(1);
      expect(component.closedRecently().length).toBe(0);
    });

    it('annuler est un droit — la parole de l’utilisateur prime', () => {
      const a = action('a-1', 'Relancer le support');
      build([a]);
      service.cancel.and.returnValue(of({ ...a, status: 'CANCELLED' }));

      component.cancel(a);
      fixture.detectChanges();
      expect(service.cancel).toHaveBeenCalledWith('w-1', 'a-1');
      expect(component.closedRecently()[0].status).toBe('CANCELLED');
    });

    it("un échec REMET l'écran dans l'état d'avant, et le dit — un écran qui ment est pire", () => {
      const a = action('a-1', 'Demander l’accès VPN');
      build([a]);
      service.close.and.returnValue(throwError(() => new Error('réseau')));

      component.markDone(a);
      fixture.detectChanges();

      expect(component.actions().length).toBe(1);
      expect(component.closedRecently().length).toBe(0);
      expect(snackBar.open).toHaveBeenCalled();
    });

    it('« Ailleurs » n’apparaît que s’il y en a, porte le nom du projet, et n’offre aucun geste', () => {
      build([], [{
        id: 'a-9', workspaceId: 'w-2', workspaceName: 'AGENOR', description: 'Valider le RSSI',
        blocks: null, person: null, kind: 'ACTION', createdAt: '2026-09-18T08:00:00Z',
      }]);

      const section = fixture.nativeElement.querySelector('.elsewhere');
      expect(section).not.toBeNull();
      expect(section.textContent).toContain('Ailleurs (1)');

      component.elsewhereOpen.set(true);
      fixture.detectChanges();
      const card = fixture.nativeElement.querySelector('.action--elsewhere');
      expect(card.textContent).toContain('AGENOR');
      expect(card.querySelector('button')).toBeNull(); // lecture seule
    });

    it('le chargement en échec le dit, sans page blanche ni détail technique', () => {
      service = jasmine.createSpyObj<TerminalActionsService>('TerminalActionsService',
        ['list', 'elsewhere', 'close', 'cancel', 'reopen']);
      service.list.and.returnValue(throwError(() => new Error('boom')));
      service.elsewhere.and.returnValue(of([]));
      TestBed.configureTestingModule({
        imports: [TerminalActionsPanelComponent, NoopAnimationsModule],
        providers: [provideHttpClient(), provideHttpClientTesting(),
          { provide: TerminalActionsService, useValue: service }],
      });
      fixture = TestBed.createComponent(TerminalActionsPanelComponent);
      fixture.componentRef.setInput('workspaceId', 'w-1');
      fixture.detectChanges();

      expect(fixture.nativeElement.textContent).toContain("n'ont pas pu être chargées");
      expect(fixture.nativeElement.textContent).not.toContain('boom');
    });
  });

  describe("l'âge, qui est le signal qui fait agir", () => {
    const now = Date.parse('2026-09-24T12:00:00Z');

    it('dit les minutes, les heures, hier, puis les jours', () => {
      expect(ageLabel('2026-09-24T11:58:00Z', now)).toBe('il y a 2 min');
      expect(ageLabel('2026-09-24T08:00:00Z', now)).toBe('il y a 4 h');
      expect(ageLabel('2026-09-23T12:00:00Z', now)).toBe('hier');
      expect(ageLabel('2026-09-20T12:00:00Z', now)).toBe('il y a 4 jours');
      expect(ageLabel('pas une date', now)).toBe('');
    });
  });

});
