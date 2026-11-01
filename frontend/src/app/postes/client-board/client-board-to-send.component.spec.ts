import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { ClientBoardToSendComponent } from './client-board-to-send.component';
import { JarvisToSend } from '../../core/models/jarvis.models';
import { JarvisService } from '../../core/services/jarvis.service';

export function toSendItem(overrides: Partial<JarvisToSend> = {}): JarvisToSend {
  return {
    workspaceId: 'w-1', workspaceName: 'Connecteur AGENOR', messageId: 'm-1', blockIndex: 0, kind: 'MAIL',
    subject: 'Compte forge', recipient: 'Zahi', proposedAt: new Date().toISOString(), sentAt: null,
    excerpt: 'Bonjour Zahi', chantierNumber: null, viaJarvis: true, ...overrides,
  };
}

/** **« À envoyer (n) » dans le tableau du client** (F-231 / SF-231-06). */
describe('À envoyer (n) dans le tableau du client (F-231 / SF-231-06)', () => {
  let service: jasmine.SpyObj<JarvisService>;

  beforeEach(() => {
    service = jasmine.createSpyObj<JarvisService>('JarvisService', ['toSend']);
    TestBed.configureTestingModule({
      imports: [ClientBoardToSendComponent],
      providers: [provideRouter([]), { provide: JarvisService, useValue: service }],
    });
  });

  it('compte et liste les encarts non marqués envoyés du poste, avec la marque Jarvis', () => {
    service.toSend.and.returnValue(of({ count: 2, items: [
      toSendItem(), toSendItem({ messageId: 'm-2', kind: 'MESSAGE', recipient: 'l’équipe', viaJarvis: false }),
    ] }));
    const fixture = TestBed.createComponent(ClientBoardToSendComponent);
    fixture.componentRef.setInput('hostId', 'h-1');
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(service.toSend).toHaveBeenCalledWith('h-1');
    expect(text).toContain('À envoyer (2)');
    expect(text).toContain('Mail à Zahi — Compte forge');
    expect(text).toContain('Message à l’équipe');
    expect(text).toContain('🎙 via Jarvis');
  });

  it('reste absent quand il n’y a rien à envoyer', () => {
    service.toSend.and.returnValue(of({ count: 0, items: [] }));
    const fixture = TestBed.createComponent(ClientBoardToSendComponent);
    fixture.componentRef.setInput('hostId', 'h-1');
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.cbs')).toBeNull();
  });
});
