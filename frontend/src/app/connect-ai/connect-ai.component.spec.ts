import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { ConnectAiComponent } from './connect-ai.component';
import { McpConnectionsService } from '../core/services/mcp-connections.service';
import { McpJournalEntry } from '../core/models/mcp-connections.models';

describe('ConnectAiComponent', () => {
  let fixture: ComponentFixture<ConnectAiComponent>;
  let component: ConnectAiComponent;
  let service: jasmine.SpyObj<McpConnectionsService>;

  beforeEach(async () => {
    service = jasmine.createSpyObj<McpConnectionsService>('McpConnectionsService', ['journal']);
    service.journal.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [ConnectAiComponent, NoopAnimationsModule],
      providers: [
        { provide: McpConnectionsService, useValue: service },
        provideRouter([]),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ConnectAiComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('builds the server url and Claude Code command from the origin', () => {
    expect(component.serverUrl()).toContain('/api/mcp');
    expect(component.claudeCodeCommand()).toContain('claude mcp add --transport http claude-gateway');
    expect(component.claudeCodeCommand()).toContain(component.serverUrl());
  });

  it('reports active when the journal has a recent entry', () => {
    const recent: McpJournalEntry = {
      id: 'j1',
      client: 'Claude Code',
      authKind: 'OAUTH',
      tool: 'session_info',
      hostId: null,
      paramsSummary: null,
      result: 'OK',
      durationMs: 12,
      createdAt: new Date().toISOString(),
    };
    service.journal.and.returnValue(of([recent]));
    component.verify();
    expect(component.verifyState()).toBe('active');
  });

  it('reports none when the journal has no recent entry', () => {
    const old: McpJournalEntry = {
      id: 'j2',
      client: 'Claude Code',
      authKind: 'OAUTH',
      tool: 'session_info',
      hostId: null,
      paramsSummary: null,
      result: 'OK',
      durationMs: 12,
      createdAt: '2020-01-01T00:00:00Z',
    };
    service.journal.and.returnValue(of([old]));
    component.verify();
    expect(component.verifyState()).toBe('none');
  });

  it('reports none when the journal call fails', () => {
    service.journal.and.returnValue(throwError(() => new Error('boom')));
    component.verify();
    expect(component.verifyState()).toBe('none');
  });
});
