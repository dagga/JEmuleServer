'use client';

import { useState } from 'react';
import type { Column } from '@/components/DataTable';
import { message, post } from '@/lib/api';

export type Client = Record<string, any>;

export const CLIENT_COLUMNS: Column<Client>[] = [
  { key: 'nickname', label: 'Nickname', width: '24ch' },
  { key: 'ip', label: 'IP', kind: 'code', width: '22ch' },
  { key: 'port', label: 'Port', kind: 'int' },
  { key: 'listenPort', label: 'Listen port', kind: 'int' },
  { key: 'clientId', label: 'Client ID', kind: 'code', width: '14ch' },
  { key: 'idType', label: 'ID' },
  { key: 'publishedFiles', label: 'Published files', kind: 'num' },
  { key: 'connectedSeconds', label: 'Connected', kind: 'seconds' },
];

/** Disconnects one client (Clients and Bots views). */
export function KickButton({ clientId, onDone }: { clientId: string; onDone: (text: string, error?: boolean) => void }) {
  const [busy, setBusy] = useState(false);
  return (
    <button
      type="button"
      className="btn btnSmall"
      disabled={busy}
      onClick={async () => {
        setBusy(true);
        try {
          await post('/api/kick', { clientId });
          onDone('Client kicked.');
        } catch (e) {
          onDone(message(e), true);
        } finally {
          setBusy(false);
        }
      }}
    >
      Kick
    </button>
  );
}
