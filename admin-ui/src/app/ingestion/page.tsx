'use client';

import { DataTable, withExtraColumns, type Column } from '@/components/DataTable';
import { Card, LoadState, PageHeader } from '@/components/ui';
import { useApi } from '@/lib/hooks';

type Channel = Record<string, any>;

export default function IngestionPage() {
  const { data, error, loading, reload } = useApi<Channel[]>('/api/ingestion');
  const rows = data ?? [];
  // Relay mode: the files of each channel go to the relay server
  const relaying = rows.some((r) => 'sentToRelay' in r);
  const count = relaying ? 'sentToRelay' : 'newFiles';
  const columns: Column<Channel>[] = [
    { key: 'source', label: 'Channel', width: '30ch' },
    { key: count, label: relaying ? 'Sent to the relay server' : 'New files', kind: 'num' },
    {
      key: 'percent', label: 'Share', kind: 'pct',
      render: (r) => (
        <span className="shareCell">
          <span className="shareBar" aria-hidden="true"><i style={{ width: Math.min(100, Number(r.percent) || 0) + '%' }} /></span>
          {(Number(r.percent) || 0).toFixed(1)} %
        </span>
      ),
    },
  ];
  return (
    <>
      <PageHeader
        title="Ingestion"
        description={relaying ? 'Files sent to the relay server since the start, by ingestion channel.' : 'New database files since the start, by ingestion channel.'}
      >
        <button type="button" className="btn" onClick={() => void reload()}>Refresh</button>
      </PageHeader>
      {data ? (
        <Card>
          <DataTable rows={rows} columns={withExtraColumns(rows, columns)} sortable={false} rowClass={(r) => (r.source === 'total' ? 'rowTotal' : undefined)} />
        </Card>
      ) : <LoadState loading={loading} error={error} />}
    </>
  );
}
