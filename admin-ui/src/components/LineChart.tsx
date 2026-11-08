'use client';

import { useMemo, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react';
import { DAY, clock, compact, day, fmt, stamp } from '@/lib/format';
import { useWidth } from '@/lib/hooks';

/** A sample of the history: [time, files, clients]. */
export type Point = number[];

interface Bucket { t: number; v: number; n: number; a: number; b: number; gap: boolean }

function ticks(lo: number, hi: number, n: number) {
  if (lo === hi) { lo -= 1; hi += 1; }
  const raw = (hi - lo) / n, mag = 10 ** Math.floor(Math.log10(raw)), e = raw / mag;
  const step = (e >= 7.5 ? 10 : e >= 3.5 ? 5 : e >= 1.5 ? 2 : 1) * mag;
  const a = Math.floor(lo / step) * step, b = Math.ceil(hi / step) * step, t: number[] = [];
  for (let v = a; v <= b + step / 2; v += step) t.push(Math.round(v * 1e6) / 1e6);
  return { a, b, t };
}

function timeTicks(t0: number, t1: number): [number, string][] {
  const out: [number, string][] = [];
  const span = t1 - t0;
  if (span <= 2 * DAY) {
    const step = (span <= 12 * 3600000 ? 2 : span <= DAY * 1.1 ? 3 : 6) * 3600000;
    for (let t = Math.ceil(t0 / step) * step; t <= t1; t += step) out.push([t, clock(t)]);
    return out;
  }
  const stepDays = span <= 8 * DAY ? 1 : span <= 16 * DAY ? 2 : 5;
  const d = new Date(t0);
  d.setHours(24, 0, 0, 0);
  for (let t = d.getTime(), i = 0; t <= t1; t += DAY, i++) if (i % stepDays === 0) out.push([t, day(t)]);
  return out;
}

/** More samples than pixels: one point per bucket of about 2 px, the average of its samples. */
function buckets(pts: Point[], k: number, interval: number, width: number): Bucket[] {
  const span = pts[pts.length - 1][0] - pts[0][0], size = Math.max(interval, span / (width / 2));
  const out: { a: number; b: number; sum: number; n: number; gap: boolean }[] = [];
  let cur: (typeof out)[number] | null = null, prev: number | null = null;
  for (const p of pts) {
    const gap = prev !== null && p[0] - prev > 3 * interval;
    if (!cur || gap || p[0] - cur.a >= size) { cur = { a: p[0], b: p[0], sum: 0, n: 0, gap }; out.push(cur); }
    cur.b = p[0]; cur.sum += p[k]; cur.n++; prev = p[0];
  }
  return out.map((c) => ({ t: c.n > 1 ? (c.a + c.b) / 2 : c.a, v: c.sum / c.n, n: c.n, a: c.a, b: c.b, gap: c.gap }));
}

const H = 240, L = 64, R = 16, T = 12, B = 28;

export function LineChart({ points, k, series, name, interval }: { points: Point[]; k: number; series: 1 | 2; name: string; interval: number }) {
  const [ref, measured] = useWidth<HTMLDivElement>();
  const [cur, setCur] = useState<number | null>(null);
  const svgRef = useRef<SVGSVGElement>(null);
  const tipRef = useRef<HTMLDivElement>(null);
  const W = Math.max(320, measured || 640), iw = W - L - R, ih = H - T - B;

  const model = useMemo(() => {
    if (points.length < 2) return null;
    const t0 = points[0][0], t1 = points[points.length - 1][0];
    const pts = buckets(points, k, interval, iw);
    let lo = Infinity, hi = -Infinity;
    for (const p of pts) { lo = Math.min(lo, p.v); hi = Math.max(hi, p.v); }
    const pad = (hi - lo) * 0.08 || 1, yt = ticks(Math.max(0, lo - pad), hi + pad, 4);
    const x = (t: number) => L + ((t - t0) / (t1 - t0 || 1)) * iw;
    const y = (v: number) => T + ih - ((v - yt.a) / (yt.b - yt.a || 1)) * ih;
    let d = '';
    pts.forEach((p, i) => { d += (i === 0 || p.gap ? 'M' : 'L') + x(p.t).toFixed(1) + ' ' + y(p.v).toFixed(1); });
    return { t0, t1, pts, yt, x, y, d };
  }, [points, k, interval, iw, ih]);

  if (!model) {
    return (
      <div className="plot" ref={ref}>
        <div className="empty">
          {points.length ? 'One sample so far: ' + fmt(points[0][k]) + '. ' : ''}The curve appears after the next samples (every 5 minutes).
        </div>
      </div>
    );
  }

  const { t0, t1, pts, yt, x, y, d } = model;
  const last = pts[pts.length - 1], lastRaw = points[points.length - 1];
  const active = cur == null ? null : pts[Math.max(0, Math.min(pts.length - 1, cur))];

  function nearest(t: number) {
    let a = 0, b = pts.length - 1;
    while (b - a > 1) { const m = (a + b) >> 1; if (pts[m].t < t) a = m; else b = m; }
    return t - pts[a].t < pts[b].t - t ? a : b;
  }
  function onMove(e: PointerEvent<SVGSVGElement>) {
    const r = e.currentTarget.getBoundingClientRect(), vx = ((e.clientX - r.left) * W) / r.width;
    setCur(nearest(t0 + ((vx - L) / iw) * (t1 - t0)));
  }
  function onKey(e: KeyboardEvent<SVGSVGElement>) {
    const at = cur ?? pts.length - 1;
    if (e.key === 'ArrowLeft') { setCur(Math.max(0, at - 1)); e.preventDefault(); }
    else if (e.key === 'ArrowRight') { setCur(Math.min(pts.length - 1, at + 1)); e.preventDefault(); }
    else if (e.key === 'Home') { setCur(0); e.preventDefault(); }
    else if (e.key === 'End') { setCur(pts.length - 1); e.preventDefault(); }
  }

  // The tooltip beside the point, on its left near the right edge
  let tipStyle: React.CSSProperties | undefined;
  if (active) {
    const scale = (svgRef.current?.getBoundingClientRect().width || W) / W;
    const px = x(active.t) * scale, py = y(active.v) * scale;
    const w = tipRef.current?.offsetWidth ?? 180;
    const left = px + 12 + w > (measured || W) ? px - w - 12 : px + 12;
    tipStyle = { left: Math.max(0, left) + 'px', top: Math.max(0, py - 48) + 'px' };
  }

  const color = 'var(--s' + series + ')';
  return (
    <div className="plot" ref={ref}>
      <svg
        ref={svgRef}
        viewBox={'0 0 ' + W + ' ' + H}
        tabIndex={0}
        role="img"
        aria-label={name + ': ' + fmt(lastRaw[k]) + ' at ' + stamp(lastRaw[0]) + '. Use the arrow keys to read the samples.'}
        onPointerMove={onMove}
        onPointerLeave={() => setCur(null)}
        onBlur={() => setCur(null)}
        onFocus={() => setCur(pts.length - 1)}
        onKeyDown={onKey}
      >
        {yt.t.map((v) => {
          const yy = y(v).toFixed(1);
          return (
            <g key={v}>
              <line x1={L} x2={W - R} y1={yy} y2={yy} className="gridLine" />
              <text x={L - 8} y={yy} dy="4" textAnchor="end" className="axisText">{compact(v)}</text>
            </g>
          );
        })}
        <line x1={L} x2={W - R} y1={T + ih} y2={T + ih} className="axisLine" />
        {timeTicks(t0, t1).map(([t, l]) => (
          <text key={t} x={x(t).toFixed(1)} y={H - 8} textAnchor="middle" className="axisText">{l}</text>
        ))}
        <path d={d} fill="none" strokeWidth="2" strokeLinejoin="round" strokeLinecap="round" stroke={color} />
        <circle cx={x(last.t).toFixed(1)} cy={y(last.v).toFixed(1)} r="4" strokeWidth="2" fill={color} className="dotRing" />
        {active && (
          <>
            <line x1={x(active.t)} x2={x(active.t)} y1={T} y2={T + ih} className="crossLine" />
            <circle cx={x(active.t)} cy={y(active.v)} r="4.5" strokeWidth="2" fill={color} className="dotRing" />
          </>
        )}
        <rect x={L} y={T} width={iw} height={ih} fill="transparent" />
      </svg>
      {active && (
        <div className="tip" ref={tipRef} style={tipStyle}>
          <strong>{fmt(Math.round(active.v))}</strong>
          {active.n > 1
            ? name + ' · average of ' + active.n + ' samples · ' + stamp(active.a) + ' to ' + clock(active.b)
            : name + ' · ' + stamp(active.a)}
        </div>
      )}
    </div>
  );
}

/** The samples as a table of days: the last sample of each day, newest first. */
export function DailyTable({ points, k, name }: { points: Point[]; k: number; name: string }) {
  const byDay = new Map<string, Point>();
  points.forEach((p) => byDay.set(day(p[0]), p));
  const rows = [...byDay.entries()].reverse();
  if (!rows.length) return <p className="sub">No sample yet.</p>;
  return (
    <div className="tableWrap">
      <table>
        <thead>
          <tr><th>Day</th><th>Last sample</th><th className="num">{name}</th></tr>
        </thead>
        <tbody>
          {rows.map(([d, p]) => (
            <tr key={d}><td>{d}</td><td>{clock(p[0])}</td><td className="num">{fmt(p[k])}</td></tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
