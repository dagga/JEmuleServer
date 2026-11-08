'use client';

import { useState } from 'react';
import { DataTable, withExtraColumns, type Column } from '@/components/DataTable';
import { Card, LoadState, Notice, PageHeader } from '@/components/ui';
import { message, post } from '@/lib/api';
import { fmt } from '@/lib/format';
import { useApi } from '@/lib/hooks';

type Banned = Record<string, any>;

const COLUMNS: Column<Banned>[] = [
  { key: 'name', label: 'File name', kind: 'code', width: '48ch' },
  { key: 'reason', label: 'Reason', width: '36ch' },
  { key: 'bannedAt', label: 'Banned', kind: 'time' },
];

export default function BlocksPage() {
  const { data, error, loading, reload } = useApi<Banned[]>('/api/blocks');
  const [name, setName] = useState('');
  const [reason, setReason] = useState('');
  const [notice, setNotice] = useState<{ text: string; error?: boolean } | null>(null);
  const rows = data ?? [];

  async function ban() {
    try {
      const r = await post<{ filesRemoved: number }>('/api/blocks', { name, reason });
      setNotice({ text: 'Name banned; ' + fmt(r.filesRemoved) + ' indexed file(s) removed.' });
      void reload();
    } catch (e) {
      setNotice({ text: message(e), error: true });
    }
  }

  async function unban(value: string) {
    try {
      const r = await post<{ wasBanned: boolean }>('/api/unblock', { name: value });
      setNotice({ text: r.wasBanned ? 'Name unbanned.' : 'This name was not banned.' });
      void reload();
    } catch (e) {
      setNotice({ text: message(e), error: true });
    }
  }

  return (
    <>
      <PageHeader title="Blocks" description="Banned file names, whatever their case and hash: the files indexed under a name are removed when it is banned.">
        <a className="btn" href="/api/blocks/export" download="banned-names.txt">Export</a>
      </PageHeader>
      <Card title="Ban or unban a name">
        <form
          className="formRow"
          onSubmit={(e) => {
            e.preventDefault();
            void ban();
          }}
        >
          <label className="field grow">
            <span>File name</span>
            <input value={name} onChange={(e) => setName(e.target.value)} placeholder="File name (any case)" />
          </label>
          <label className="field grow">
            <span>Reason</span>
            <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Reason" maxLength={240} />
          </label>
          <div className="formButtons">
            <button type="submit" className="btn btnDanger">Ban name</button>
            <button type="button" className="btn" onClick={() => void unban(name)}>Unban name</button>
          </div>
        </form>
      </Card>
      <Notice text={notice?.text ?? null} error={notice?.error} />
      {data ? (
        <Card title="Most recently banned names" aside={<span className="sub">the last {fmt(rows.length)}; Export gives every name</span>}>
          <DataTable
            rows={rows}
            columns={withExtraColumns(rows, COLUMNS)}
            rowKey={(r, i) => r.name + ':' + i}
            tall
            actions={(r) => <button type="button" className="btn btnSmall" onClick={() => void unban(String(r.name))}>Unban</button>}
          />
        </Card>
      ) : <LoadState loading={loading} error={error} />}
    </>
  );
}
