'use client';

import { useState } from 'react';
import { DataTable, withExtraColumns, type Column } from '@/components/DataTable';
import { KickButton, type Client } from '@/components/KickButton';
import { Card, LoadState, Notice, PageHeader } from '@/components/ui';
import { fmt } from '@/lib/format';
import { useApi } from '@/lib/hooks';

const COLUMNS: Column<Client>[] = [
  { key: 'nickname', label: 'Nickname', width: '28ch' },
  { key: 'ip', label: 'IP', kind: 'code', width: '22ch' },
  { key: 'clientId', label: 'Client ID', kind: 'code', width: '14ch' },
  { key: 'classification', label: 'Classification', width: '26ch' },
];

export default function BotsPage() {
  const { data, error, loading, reload } = useApi<Client[]>('/api/bots');
  const [notice, setNotice] = useState<{ text: string; error?: boolean } | null>(null);
  const rows = data ?? [];
  return (
    <>
      <PageHeader title="Bots" description="Potential bots, matched by nickname only: a heuristic, never a verification or a block.">
        <button type="button" className="btn" onClick={() => void reload()}>Refresh</button>
      </PageHeader>
      <Notice text={notice?.text ?? null} error={notice?.error} />
      {data ? (
        <Card title={'Potential bots matched by nickname only: ' + fmt(rows.length)}>
          <DataTable
            rows={rows}
            columns={withExtraColumns(rows, COLUMNS)}
            tall
            actions={(r) => r.clientId ? (
              <KickButton clientId={String(r.clientId)} onDone={(text, err) => { setNotice({ text, error: err }); if (!err) void reload(); }} />
            ) : null}
          />
        </Card>
      ) : <LoadState loading={loading} error={error} />}
    </>
  );
}
