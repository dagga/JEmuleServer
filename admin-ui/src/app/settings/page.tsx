'use client';

import { useMemo, useState } from 'react';
import { Card, LoadState, Notice, PageHeader } from '@/components/ui';
import { message, post, summarize, type ConfigResult } from '@/lib/api';
import { useApi } from '@/lib/hooks';

interface Parameter {
  key: string;
  value: string | number | boolean;
  type: 'INTEGER' | 'FLOAT' | 'BOOLEAN' | 'STRING' | string;
  scope: string;
  scopeLabel?: string;
  description: string;
  pending: string;
}

const original = (p: Parameter) => String(p.value);

export default function SettingsPage() {
  const { data, error, loading, reload } = useApi<Parameter[]>('/api/settings');
  // Edited values, by key, as the form would send them
  const [edits, setEdits] = useState<Record<string, string>>({});
  const [filter, setFilter] = useState('');
  const [notice, setNotice] = useState<{ text: string; error?: boolean } | null>(null);
  const [busy, setBusy] = useState(false);

  const changed = useMemo(() => {
    const out: Record<string, string> = {};
    for (const p of data ?? []) if (p.key in edits && edits[p.key] !== original(p)) out[p.key] = edits[p.key];
    return out;
  }, [data, edits]);
  const changes = Object.keys(changed).length;

  const shown = useMemo(() => {
    const q = filter.trim().toLowerCase();
    if (!q) return data ?? [];
    return (data ?? []).filter((p) => (p.key + ' ' + p.description + ' ' + p.scope).toLowerCase().includes(q));
  }, [data, filter]);

  async function save() {
    if (!changes) {
      setNotice({ text: 'No change.' });
      return;
    }
    setBusy(true);
    try {
      const r = await post<ConfigResult>('/api/settings', changed);
      const failed = !!r.errors?.length;
      setNotice({ text: summarize(r), error: failed });
      // Nothing is applied when a value is invalid: the edits stay to be corrected
      if (!failed) {
        setEdits({});
        await reload();
      }
    } catch (e) {
      setNotice({ text: message(e), error: true });
    } finally {
      setBusy(false);
    }
  }

  async function reloadFromFile() {
    setBusy(true);
    try {
      const r = await post<ConfigResult>('/api/settings/reload');
      setNotice({ text: summarize(r), error: !!r.errors?.length });
      setEdits({});
      await reload();
    } catch (e) {
      setNotice({ text: message(e), error: true });
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <PageHeader
        title="Settings"
        description="Every server.properties parameter. Hot values apply at once, admin values rebuild the admin interfaces, restart values wait for the next restart."
      >
        <input type="search" value={filter} onChange={(e) => setFilter(e.target.value)} placeholder="Filter" aria-label="Filter settings" />
        <button type="button" className="btn" disabled={busy} onClick={reloadFromFile}>Reload from file</button>
        <button type="button" className="btn btnPrimary" disabled={busy} onClick={save}>
          Save changes{changes ? ' (' + changes + ')' : ''}
        </button>
      </PageHeader>
      <Notice text={notice?.text ?? null} error={notice?.error} />
      {data ? (
        <Card>
          <div className="settings" role="table" aria-label="Settings">
            <div className="settingsHead" role="row">
              <span role="columnheader">Key</span>
              <span role="columnheader">Value</span>
              <span role="columnheader">Scope</span>
              <span role="columnheader">Description</span>
            </div>
            {shown.map((p) => {
              const value = p.key in edits ? edits[p.key] : original(p);
              const dirty = p.key in changed;
              const id = 'set_' + p.key;
              return (
                <div key={p.key} className={'settingRow' + (dirty ? ' dirty' : '')} role="row">
                  <label htmlFor={id} className="settingKey mono" role="rowheader" title={p.key}>{p.key}</label>
                  <div className="settingValue" role="cell">
                    {p.type === 'BOOLEAN' ? (
                      <input
                        type="checkbox"
                        id={id}
                        checked={value === 'true'}
                        onChange={(e) => setEdits((x) => ({ ...x, [p.key]: String(e.target.checked) }))}
                      />
                    ) : (
                      <input
                        id={id}
                        value={value}
                        inputMode={p.type === 'INTEGER' ? 'numeric' : p.type === 'FLOAT' ? 'decimal' : undefined}
                        spellCheck={false}
                        onChange={(e) => setEdits((x) => ({ ...x, [p.key]: e.target.value }))}
                      />
                    )}
                    {p.pending !== '' && p.pending != null && <small className="pending" title={p.pending}>After restart: {p.pending}</small>}
                  </div>
                  <div role="cell"><span className={'badge scope-' + p.scope} title={p.scopeLabel}>{p.scope}</span></div>
                  <div className="settingDesc sub" role="cell">{p.description}</div>
                </div>
              );
            })}
            {!shown.length && <div className="empty">No matching setting.</div>}
          </div>
        </Card>
      ) : <LoadState loading={loading} error={error} />}
    </>
  );
}
