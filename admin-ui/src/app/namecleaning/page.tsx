'use client';

import { useState } from 'react';
import { DataTable, type Column } from '@/components/DataTable';
import { Card, LoadState, Meter, Notice, PageHeader, State, Tile } from '@/components/ui';
import { message, post } from '@/lib/api';
import { fmt, fullTime, relative } from '@/lib/format';
import { useApi, useInterval } from '@/lib/hooks';

interface Rule { text: string; addedAt?: unknown }

interface Job {
  state?: string;
  scanned?: number;
  renamed?: number;
  total?: number;
  startedAt?: unknown;
  finishedAt?: unknown;
  lastError?: string | null;
}

interface NameCleaning { rules: Rule[]; cleanedAtIngestion: number; job?: Job }

const RUNNING = ['running', 'starting', 'stopping'];

const isRunning = (job?: Job) => RUNNING.includes(String(job?.state ?? '').toLowerCase());

const COLUMNS: Column<Rule & Record<string, any>>[] = [
  {
    key: 'text', label: 'Text removed from file names',
    // Quoted: leading and trailing spaces count
    render: (r) => <span className="trunc mono" title={r.text}>&ldquo;{r.text}&rdquo;</span>,
  },
  { key: 'addedAt', label: 'Added', kind: 'time' },
];

export default function NameCleaningPage() {
  const { data, error, status, loading, reload } = useApi<NameCleaning>('/api/namecleaning');
  const [text, setText] = useState('');
  const [notice, setNotice] = useState<{ text: string; error?: boolean } | null>(null);
  const job = data?.job;
  const running = isRunning(job);
  // The progress of a running job every 2 s
  useInterval(() => void reload(), running ? 2_000 : null);

  async function act(path: string, form: Record<string, string>, done: string) {
    try {
      await post(path, form);
      setNotice({ text: done });
      if (form.action === 'add') setText('');
      void reload();
    } catch (e) {
      setNotice({ text: message(e), error: true });
    }
  }

  if (status === 404) {
    return (
      <>
        <PageHeader title="Name cleaning" />
        <div className="card empty">This server has no name cleaning.</div>
      </>
    );
  }

  const progress = job?.total ? Math.min(1, (job.scanned ?? 0) / job.total) : null;
  return (
    <>
      <PageHeader title="Name cleaning" description="Texts removed from file names: at ingestion, and on request from the files already indexed.">
        <button type="button" className="btn" onClick={() => void reload()}>Refresh</button>
      </PageHeader>
      <Notice text={notice?.text ?? null} error={notice?.error} />
      {data ? (
        <>
          <div className="grid kpis">
            <Tile label="Rules" value={fmt(data.rules.length)} sub="texts removed from the names of new files" />
            <Tile label="Cleaned at ingestion" value={fmt(data.cleanedAtIngestion)} sub="file names cleaned as they were indexed, since the last start" />
          </div>

          <Card title="Rules">
            <form
              className="formRow"
              onSubmit={(e) => {
                e.preventDefault();
                if (text.trim()) void act('/api/namecleaning', { action: 'add', text }, 'Rule added.');
              }}
            >
              <label className="field grow">
                <span>Text to remove</span>
                <input value={text} onChange={(e) => setText(e.target.value)} placeholder="e.g. [www.example.net]" />
              </label>
              <div className="formButtons">
                <button type="submit" className="btn btnPrimary" disabled={!text.trim()}>Add rule</button>
              </div>
            </form>
            <DataTable
              rows={data.rules as (Rule & Record<string, any>)[]}
              columns={COLUMNS}
              rowKey={(r) => r.text}
              empty="No rule yet."
              actions={(r) => (
                <button type="button" className="btn btnSmall" onClick={() => void act('/api/namecleaning', { action: 'remove', text: r.text }, 'Rule removed.')}>
                  Remove
                </button>
              )}
            />
          </Card>

          <Card
            title="Apply to the files already indexed"
            aside={running
              ? <button type="button" className="btn btnDanger" onClick={() => void act('/api/namecleaning/apply', { action: 'stop' }, 'Stop requested.')}>Stop</button>
              : <button type="button" className="btn btnPrimary" disabled={!data.rules.length} onClick={() => void act('/api/namecleaning/apply', { action: 'start' }, 'Cleaning started.')}>Start</button>}
          >
            {job ? (
              <>
                {running ? <State level="good" label={String(job.state).toLowerCase() === 'running' ? 'Running' : 'Running · ' + job.state} />
                  : job.lastError ? <State level="critical" label={'Failed: ' + job.lastError} />
                    : <span className="sub">State: {job.state || 'idle'}</span>}
                {running && (progress != null ? <Meter ratio={progress} /> : <div className="meter indeterminate" aria-hidden="true"><i /></div>)}
                <dl className="facts">
                  <div><dt>Files scanned</dt><dd className="num">{fmt(job.scanned ?? 0)}{job.total ? ' of ' + fmt(job.total) : ''}</dd></div>
                  <div><dt>Files renamed</dt><dd className="num">{fmt(job.renamed ?? 0)}</dd></div>
                  <div><dt>Started</dt><dd title={fullTime(job.startedAt)}>{relative(job.startedAt)}</dd></div>
                  <div><dt>Finished</dt><dd title={fullTime(job.finishedAt)}>{running ? '–' : relative(job.finishedAt)}</dd></div>
                </dl>
                {job.lastError && running && <p className="errorText">Last error: {job.lastError}</p>}
              </>
            ) : <div className="sub">Not run since the last start.</div>}
          </Card>
        </>
      ) : <LoadState loading={loading} error={error} />}
    </>
  );
}
