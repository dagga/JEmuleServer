'use client';

import { useMemo, useState } from 'react';
import { DataTable, useRowFilter, withExtraColumns, type Column } from '@/components/DataTable';
import { ConfirmDialog } from '@/components/Dialog';
import { Card, LoadState, Notice, PageHeader } from '@/components/ui';
import { message, post } from '@/lib/api';
import { fmt } from '@/lib/format';
import { useApi } from '@/lib/hooks';

type ServerRow = Record<string, any>;

interface Ban { ip: string; reason?: string; bannedAt?: unknown }

const STATE_ORDER: Record<string, number> = { verified: 0, probing: 1, candidate: 2, rejected: 3 };

const COLUMNS: Column<ServerRow>[] = [
  { key: 'name', label: 'Name', width: '28ch' },
  {
    key: 'state', label: 'State',
    render: (r) => <span className={'badge badge-' + String(r.state)}>{r.state}{r.banned ? ' · banned' : ''}</span>,
    sortValue: (r) => STATE_ORDER[r.state] ?? 9,
  },
  { key: 'source', label: 'Source', width: '16ch' },
  { key: 'address', label: 'Address', kind: 'code', width: '22ch' },
  { key: 'tcpPort', label: 'TCP port', kind: 'int' },
  { key: 'udpPort', label: 'UDP port', kind: 'int' },
  { key: 'users', label: 'Users', kind: 'num' },
  { key: 'files', label: 'Files', kind: 'num' },
  { key: 'maxUsers', label: 'Max users', kind: 'num' },
  { key: 'lowIdUsers', label: 'LowID users', kind: 'num' },
  { key: 'udpKey', label: 'UDP key', kind: 'bool' },
  { key: 'latencyMs', label: 'Latency', kind: 'num', render: (r) => (r.latencyMs == null ? <span className="muted">–</span> : fmt(r.latencyMs) + ' ms') },
  { key: 'lastStatusSecondsAgo', label: 'Last status', kind: 'ago', hint: 'Time since the last status answer' },
  { key: 'verifiedSecondsAgo', label: 'Verified', kind: 'ago', hint: 'Time since the server was verified' },
  { key: 'missedProbes', label: 'Missed probes', kind: 'num' },
];

const BAN_COLUMNS: Column<Ban & Record<string, any>>[] = [
  { key: 'ip', label: 'IP', kind: 'code', width: '24ch' },
  { key: 'reason', label: 'Reason', width: '40ch' },
  { key: 'bannedAt', label: 'Banned', kind: 'time' },
];

export default function ServersPage() {
  const servers = useApi<ServerRow[]>('/api/servers');
  // Server bans: hidden on a server without them (404)
  const bans = useApi<Ban[]>('/api/servers/bans');
  const bansSupported = bans.data != null && bans.status !== 404;
  const [filter, setFilter] = useState('');
  const [notice, setNotice] = useState<{ text: string; error?: boolean } | null>(null);
  const [banTarget, setBanTarget] = useState<ServerRow | null>(null);
  const rows = useRowFilter(servers.data ?? [], filter);
  const counts = useMemo(() => {
    const c: Record<string, number> = {};
    for (const s of servers.data ?? []) c[s.state] = (c[s.state] ?? 0) + 1;
    return c;
  }, [servers.data]);
  const columns = useMemo(() => {
    const base = withExtraColumns(servers.data ?? [], COLUMNS);
    // The banned flag is in the state badge
    return base.filter((c) => c.key !== 'banned');
  }, [servers.data]);

  async function act(path: string, data: Record<string, string>, done: string) {
    try {
      await post(path, data);
      setNotice({ text: done });
      void servers.reload();
      void bans.reload();
    } catch (e) {
      setNotice({ text: message(e), error: true });
    }
  }

  function refresh() {
    void servers.reload();
    void bans.reload();
  }

  return (
    <>
      <PageHeader title="Servers" description="Every known eD2K server (configured, server.met, client-reported or gossip-discovered) and its state.">
        <input type="search" value={filter} onChange={(e) => setFilter(e.target.value)} placeholder="Filter" aria-label="Filter servers" />
        <button type="button" className="btn" onClick={refresh}>Refresh</button>
      </PageHeader>
      <Notice text={notice?.text ?? null} error={notice?.error} />
      {servers.data ? (
        <Card
          title={fmt(servers.data.length) + ' known servers'}
          aside={<span className="sub">{Object.entries(counts).map(([k, v]) => fmt(v) + ' ' + k).join(' · ')}{filter ? ' · ' + fmt(rows.length) + ' shown' : ''}</span>}
        >
          <DataTable
            rows={rows}
            columns={columns}
            rowKey={(r, i) => r.address + ':' + r.tcpPort + ':' + i}
            rowClass={(r) => (r.banned ? 'rowBanned' : undefined)}
            tall
            actions={bansSupported ? (r) => r.banned
              ? <button type="button" className="btn btnSmall" onClick={() => act('/api/servers/unban', { ip: String(r.address) }, 'Server unbanned.')}>Unban</button>
              : <button type="button" className="btn btnSmall btnDangerGhost" onClick={() => setBanTarget(r)}>Ban</button>
              : undefined}
          />
        </Card>
      ) : <LoadState loading={servers.loading} error={servers.error} />}

      {bansSupported && (
        <Card title={'Banned servers: ' + fmt(bans.data!.length)}>
          <DataTable
            rows={bans.data as (Ban & Record<string, any>)[]}
            columns={withExtraColumns(bans.data as (Ban & Record<string, any>)[], BAN_COLUMNS)}
            rowKey={(r) => r.ip}
            empty="No banned server."
            actions={(r) => (
              <button type="button" className="btn btnSmall" onClick={() => act('/api/servers/unban', { ip: r.ip }, 'Server unbanned.')}>Unban</button>
            )}
          />
        </Card>
      )}

      <ConfirmDialog
        open={banTarget != null}
        title="Ban this server"
        inputLabel="Reason"
        inputPlaceholder="Why this server is banned (optional)"
        confirmLabel="Ban server"
        danger
        onCancel={() => setBanTarget(null)}
        onConfirm={(reason) => {
          const target = banTarget;
          setBanTarget(null);
          if (target) void act('/api/servers/ban', { ip: String(target.address), reason }, 'Server banned.');
        }}
      >
        {banTarget && (
          <p>
            <strong>{banTarget.name || 'Unnamed server'}</strong> <span className="mono">{banTarget.address}:{banTarget.tcpPort}</span>
            <br />
            <span className="sub">The ban applies to its IP address and lasts until it is unbanned.</span>
          </p>
        )}
      </ConfirmDialog>
    </>
  );
}
