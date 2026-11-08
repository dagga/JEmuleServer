'use client';

import { useState } from 'react';
import { DataTable, useRowFilter, withExtraColumns, type Column } from '@/components/DataTable';
import { Card, LoadState, PageHeader } from '@/components/ui';
import { fmt } from '@/lib/format';
import { useApi } from '@/lib/hooks';

type Peer = Record<string, any>;

const COLUMNS: Column<Peer>[] = [
  { key: 'name', label: 'Name', width: '30ch' },
  { key: 'type', label: 'Type' },
  { key: 'address', label: 'Address', kind: 'code', width: '22ch' },
  { key: 'tcpPort', label: 'TCP port', kind: 'int' },
  { key: 'udpPort', label: 'UDP port', kind: 'int' },
  { key: 'kadVersion', label: 'Kad version', kind: 'int' },
  { key: 'lastSeen', label: 'Last seen', kind: 'time' },
];

export default function PeersPage() {
  const { data, error, loading, reload } = useApi<Peer[]>('/api/peers');
  const [filter, setFilter] = useState('');
  const rows = useRowFilter(data ?? [], filter);
  const servers = (data ?? []).filter((p) => p.type === 'eD2K server').length;
  return (
    <>
      <PageHeader title="Peers" description="Verified named eD2K server peers, then the contacts of the Kad node.">
        <input type="search" value={filter} onChange={(e) => setFilter(e.target.value)} placeholder="Filter" aria-label="Filter peers" />
        <button type="button" className="btn" onClick={() => void reload()}>Refresh</button>
      </PageHeader>
      {data ? (
        <Card
          title={fmt(data.length) + ' peers'}
          aside={<span className="sub">{fmt(servers)} verified eD2K servers · {fmt(data.length - servers)} Kad contacts{filter ? ' · ' + fmt(rows.length) + ' shown' : ''}</span>}
        >
          <DataTable rows={rows} columns={withExtraColumns(data, COLUMNS)} tall />
        </Card>
      ) : <LoadState loading={loading} error={error} />}
    </>
  );
}
