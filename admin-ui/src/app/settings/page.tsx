'use client';

import { useMemo, useState } from 'react';
import { Card, LoadState, Notice, PageHeader } from '@/components/ui';
import { message, post, summarize, type ConfigResult } from '@/lib/api';
import { useApi, useHash } from '@/lib/hooks';
import { sectionsOf } from '@/components/Shell';

interface Parameter {
  key: string;
  value: string | number | boolean;
  type: 'INTEGER' | 'FLOAT' | 'BOOLEAN' | 'STRING' | string;
  scope: string;
  scopeLabel?: string;
  /** The group of the Settings sub-menu (ConfigParameter.Category). */
  category?: string;
  categoryLabel?: string;
  description: string;
  pending: string;
  /** A text that may span several lines (login messages): edited in a text area. */
  multiline?: boolean;
}

// A multi-line text shows the \n typed before the text area existed as the line break it stands for
const original = (p: Parameter) =>
  p.multiline ? String(p.value).replace(/\r\n/g, '\n').replace(/\\n/g, '\n') : String(p.value);

const rows = (text: string) => Math.min(12, Math.max(3, text.split('\n').length + 1));

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

  // The group chosen in the sub-menu (#kad...); a filter searches every group
  const hash = useHash();
  const sections = useMemo(() => sectionsOf(data ?? []), [data]);
  const section = sections.find((x) => x.id === hash) ?? null;

  const shown = useMemo(() => {
    const q = filter.trim().toLowerCase();
    if (q) return (data ?? []).filter((p) => (p.key + ' ' + p.description + ' ' + p.scope + ' ' + (p.categoryLabel ?? '')).toLowerCase().includes(q));
    return (data ?? []).filter((p) => !section || p.category === section.id);
  }, [data, filter, section]);

  // The parameters shown, by group, in the order of the sub-menu
  const groups = useMemo(() => {
    const out: { id: string; label: string; parameters: Parameter[] }[] = [];
    for (const p of shown) {
      const id = p.category ?? 'other';
      let group = out.find((g) => g.id === id);
      if (!group) out.push(group = { id, label: p.categoryLabel ?? 'Other', parameters: [] });
      group.parameters.push(p);
    }
    return out;
  }, [shown]);

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

  function row(p: Parameter) {
    const value = p.key in edits ? edits[p.key] : original(p);
    const dirty = p.key in changed;
    const id = 'set_' + p.key;
    return (
      <div key={p.key} className={'settingRow' + (dirty ? ' dirty' : '')} role="row">
        <label htmlFor={id} className="settingKey" role="rowheader" title={p.key}>{p.key}</label>
        <div className="settingValue" role="cell">
          {p.type === 'BOOLEAN' ? (
            <input
              type="checkbox"
              id={id}
              checked={value === 'true'}
              onChange={(e) => setEdits((x) => ({ ...x, [p.key]: String(e.target.checked) }))}
            />
          ) : p.multiline ? (
            <textarea
              id={id}
              value={value}
              rows={rows(value)}
              spellCheck={false}
              onChange={(e) => setEdits((x) => ({ ...x, [p.key]: e.target.value }))}
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
  }

  return (
    <>
      <PageHeader
        title={section && !filter.trim() ? 'Settings · ' + section.label : 'Settings'}
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
        groups.length ? groups.map((g) => {
          const dirty = g.parameters.filter((p) => p.key in changed).length;
          return (
            <Card key={g.id} title={g.label} aside={<span className="sectionCount">{g.parameters.length} settings{dirty ? ' · ' + dirty + ' changed' : ''}</span>}>
              <div className="settings" role="table" aria-label={g.label}>
                <div className="settingsHead" role="row">
                  <span role="columnheader">Key</span>
                  <span role="columnheader">Value</span>
                  <span role="columnheader">Scope</span>
                  <span role="columnheader">Description</span>
                </div>
                {g.parameters.map(row)}
              </div>
            </Card>
          );
        }) : <Card><div className="empty">No matching setting.</div></Card>
      ) : <LoadState loading={loading} error={error} />}
    </>
  );
}
