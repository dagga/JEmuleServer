'use client';

import { useState } from 'react';
import { DataTable, withExtraColumns } from '@/components/DataTable';
import { CLIENT_COLUMNS, KickButton, type Client } from '@/components/KickButton';
import { Card, LoadState, Notice, PageHeader } from '@/components/ui';
import { fmt } from '@/lib/format';
import { useApi } from '@/lib/hooks';

interface ClientPage { total: number; offset: number; limit: number; clients: Client[] }

export default function ClientsPage() {
  const [input, setInput] = useState('');
  const [query, setQuery] = useState('');
  const [notice, setNotice] = useState<{ text: string; error?: boolean } | null>(null);
  const { data, error, loading, reload } = useApi<ClientPage>('/api/clients' + (query ? '?q=' + encodeURIComponent(query) : ''));
  const rows = data?.clients ?? [];

  return (
    <>
      <PageHeader title="Clients" description="Connected eD2K clients, searched by the server.">
        <form
          className="toolbar"
          role="search"
          onSubmit={(e) => {
            e.preventDefault();
            setNotice(null);
            if (input.trim() === query) void reload();
            setQuery(input.trim());
          }}
        >
          <input type="search" value={input} onChange={(e) => setInput(e.target.value)} placeholder="Search nickname, IP, or client ID" aria-label="Search clients" />
          <button type="submit" className="btn btnPrimary">Search</button>
        </form>
      </PageHeader>
      <Notice text={notice?.text ?? null} error={notice?.error} />
      {data ? (
        <Card
          title={'Matching clients: ' + fmt(data.total)}
          aside={<span className="sub">showing {fmt(rows.length)}; limit {fmt(data.limit)}</span>}
        >
          <DataTable
            rows={rows}
            columns={withExtraColumns(rows, CLIENT_COLUMNS)}
            rowKey={(r, i) => r.clientId + ':' + r.ip + ':' + i}
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
