import { JarvisNotice, noticeIcon, toSpeak } from './jarvis-notices';

function n(id: string, updatedAt: string, spoken = false): JarvisNotice {
  return {
    id, kind: 'MEETING_SOON', text: id, speech: id, link: null, count: 1, hostId: null, workspaceId: null,
    updatedAt, expiresAt: '2026-11-08T00:00:00Z', spoken,
  };
}

describe('jarvis-notices (F-249 / SF-249-08)', () => {
  it('toSpeak : les non dites, les plus anciennes d’abord', () => {
    const list = toSpeak([n('b', '2026-11-07T10:05:00Z'), n('a', '2026-11-07T10:00:00Z'), n('c', '2026-11-07T09:00:00Z', true)]);
    expect(list.map((x) => x.id)).toEqual(['a', 'b']);
  });

  it('une icône par genre', () => {
    expect(noticeIcon('MAILS_ON_SUBJECT')).toBe('mark_email_unread');
    expect(noticeIcon('MEETING_SOON')).toBe('event');
    expect(noticeIcon('DEPOSIT_REMINDER')).toBe('drive_folder_upload');
    expect(noticeIcon('MORNING_RECAP')).toBe('wb_sunny');
    expect(noticeIcon('IGNORE_OFFER')).toBe('filter_alt');
  });
});
