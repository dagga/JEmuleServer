'use client';

import { useEffect, useMemo, useState } from 'react';
import { KeyValueTable } from '@/components/DataTable';
import { LineChart, type Point } from '@/components/LineChart';
import { Card, LoadState, PageHeader } from '@/components/ui';
import { clock, duration, fmt, fullTime, stamp } from '@/lib/format';
import { useApi, useInterval } from '@/lib/hooks';

/** A figure the server samples every minute: a level, or a count since the start. */
interface Metric {
  key: string;
  label: string;
  unit: string;
  kind: 'gauge' | 'counter';
}

interface History {
  intervalSeconds: number;
  metrics: Metric[];
  /** [time, value of each metric...], null when a value could not be read. */
  points: (number | null)[][];
}

const RANGES: [number, string][] = [[1, '1 h'], [6, '6 h'], [24, '24 h']];
const KEY = 'jemule.status.metric';

// The metric chosen stays from one visit to the next; the storage may be refused (private window)
function savedMetric(): string {
  try {
    return window.localStorage.getItem(KEY) ?? 'connectedClients';
  } catch {
    return 'connectedClients';
  }
}

/** The figures of /api/status no curve can tell anything about, made readable. */
function tableFigures(status: Record<string, unknown>, drawn: Set<string>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(status)) {
    if (drawn.has(k)) continue;
    if (k === 'uptimeSeconds' && typeof v === 'number') out['uptime'] = duration(v);
    else if (k === 'lastFilePurgeAt') out['lastFilePurge'] = typeof v === 'number' && v > 0 ? fullTime(v) : 'never';
    else out[k] = v;
  }
  return out;
}

/** The curve of one metric: a level as sampled, a count as its increase per sample (gaps where unknown). */
function series(history: History, index: number, kind: Metric['kind'], hours: number): Point[] {
  const from = Date.now() - hours * 3_600_000;
  const out: Point[] = [];
  let previous: number | null = null;
  for (const p of history.points) {
    const t = p[0] as number, v = p[index + 1];
    if (v == null) { previous = null; continue; }
    if (kind === 'counter') {
      // The count restarts with the server: a drop is a new start, not a negative increase
      if (previous != null && t >= from) out.push([t, Math.max(0, v - previous)]);
      previous = v;
    } else if (t >= from) {
      out.push([t, v]);
    }
  }
  return out;
}

export default function StatusPage() {
  const status = useApi<Record<string, unknown>>('/api/status');
  const history = useApi<History>('/api/status/history');
  const [key, setKey] = useState('connectedClients');
  const [hours, setHours] = useState(6);

  useEffect(() => setKey(savedMetric()), []);
  // A sample a minute on the server: the view follows it
  useInterval(() => {
    void status.reload();
    void history.reload();
  }, 60_000);

  function choose(next: string) {
    setKey(next);
    try {
      window.localStorage.setItem(KEY, next);
    } catch {
      // not remembered, still shown
    }
  }

  const metrics = history.data?.metrics ?? [];
  const index = Math.max(0, metrics.findIndex((m) => m.key === key));
  const metric: Metric | undefined = metrics[index];
  const points = useMemo(
    () => (history.data && metric ? series(history.data, index, metric.kind, hours) : []),
    [history.data, metric, index, hours],
  );
  const interval = (history.data?.intervalSeconds ?? 60) * 1000;
  const last = history.data?.points.at(-1);
  const current = last && metric ? last[index + 1] : null;
  const unit = (m: Metric) => (m.kind === 'counter' ? m.unit + ' per minute' : m.unit);
  const drawn = useMemo(() => new Set(metrics.map((m) => m.key)), [metrics]);

  return (
    <>
      <PageHeader title="Status" description="The server's figures over the last day, sampled every minute; the others in the table below.">
        <button type="button" className="btn" onClick={() => { void status.reload(); void history.reload(); }}>Refresh</button>
      </PageHeader>

      {history.data && metric ? (
        <Card
          className="chartCard"
          title="Over time"
          aside={<span className="sectionCount">{fmt(history.data.points.length)} samples · in memory since the start, a day at most</span>}
        >
          <div className="chartControls">
            <label className="field grow">
              <span>Figure</span>
              <select value={metric.key} onChange={(e) => choose(e.target.value)} aria-label="Figure drawn">
                <optgroup label="Levels">
                  {metrics.filter((m) => m.kind === 'gauge').map((m) => (
                    <option key={m.key} value={m.key}>{m.label}{m.unit ? ' (' + m.unit + ')' : ''}</option>
                  ))}
                </optgroup>
                <optgroup label="Events, per minute">
                  {metrics.filter((m) => m.kind === 'counter').map((m) => (
                    <option key={m.key} value={m.key}>{m.label}</option>
                  ))}
                </optgroup>
              </select>
            </label>
            <div className="seg" role="group" aria-label="Range of the curve">
              {RANGES.map(([h, l]) => (
                <button key={h} type="button" aria-pressed={h === hours} onClick={() => setHours(h)}>{l}</button>
              ))}
            </div>
          </div>
          <div className="chartNow">
            <span className="big">{current == null ? '–' : fmt(Math.round(current * 100) / 100)}</span>
            <span className="sub">
              {metric.kind === 'counter'
                ? (metric.unit ? metric.unit + ' ' : '') + 'since the start · the curve shows them per minute'
                : (metric.unit ? metric.unit + ' · ' : '') + 'now'}
              {last ? ' · ' + clock(last[0] as number) : ''}
            </span>
          </div>
          <LineChart points={points} k={1} series={metric.kind === 'counter' ? 2 : 1} name={metric.label + (unit(metric) ? ' (' + unit(metric) + ')' : '')} interval={interval} />
          <details>
            <summary>Show as table</summary>
            <SampleTable points={points} name={metric.label} unit={unit(metric)} />
          </details>
        </Card>
      ) : <LoadState loading={history.loading} error={history.error} />}

      {status.data ? (
        <Card title="Current state" aside={<span className="sectionCount">figures without a curve</span>}>
          <KeyValueTable data={tableFigures(status.data, drawn)} />
        </Card>
      ) : <LoadState loading={status.loading} error={status.error} />}
    </>
  );
}

/** The samples of the curve, newest first, an hour at most. */
function SampleTable({ points, name, unit }: { points: Point[]; name: string; unit: string }) {
  const rows = points.slice(-60).reverse();
  if (!rows.length) return <p className="sub">No sample yet.</p>;
  return (
    <div className="tableWrap">
      <table>
        <thead>
          <tr><th>Time</th><th className="num">{name}{unit ? ' (' + unit + ')' : ''}</th></tr>
        </thead>
        <tbody>
          {rows.map((p) => (
            <tr key={p[0]}><td>{stamp(p[0])}</td><td className="num">{fmt(Math.round(p[1] * 100) / 100)}</td></tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
