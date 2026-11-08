'use client';

import { DataTable, withExtraColumns, type Column } from '@/components/DataTable';
import { Card, LoadState, PageHeader } from '@/components/ui';
import { useApi } from '@/lib/hooks';

type Channel = Record<string, any>;

const COLUMNS: Column<Channel>[] = [
  { key: 'channel', label: 'Channel', width: '30ch' },
  { key: 'queries', label: 'Queries', kind: 'num', hint: 'Searches forked, nodes followed or UDP global searches received' },
  { key: 'requests', label: 'Requests', kind: 'num', hint: 'Requests sent, pages asked or searches started on Kad' },
  { key: 'results', label: 'Results', kind: 'num', hint: 'Results returned or pages answered' },
  { key: 'ingested', label: 'Ingested', kind: 'num', hint: 'Files handed over to the index' },
  { key: 'active', label: 'Active', kind: 'num', hint: 'Searches running now' },
  { key: 'blocked', label: 'Blocked', kind: 'num', hint: 'Illicit files blocked, or global searches refused' },
  { key: 'skipped', label: 'Skipped', kind: 'num', hint: 'Duplicates, no keyword or Kad off' },
];

export default function SearchesPage() {
  const { data, error, loading, reload } = useApi<Channel[]>('/api/searches');
  const rows = data ?? [];
  return (
    <>
      <PageHeader title="Searches" description="Kad and eD2K server search counters since the start: queries sent and results returned.">
        <button type="button" className="btn" onClick={() => void reload()}>Refresh</button>
      </PageHeader>
      {data ? (
        <Card>
          <DataTable rows={rows} columns={withExtraColumns(rows, COLUMNS)} sortable={false} />
          <p className="sub footnote">
            Kad background pages: nodes followed past the search limit, pages asked and answered, files accepted. UDP global
            searches: queries of other servers&apos; clients, searches started on Kad, clean files found, refused and skipped
            queries; their Kad traffic is also counted in the Kad row.
          </p>
        </Card>
      ) : <LoadState loading={loading} error={error} />}
    </>
  );
}
