'use client';

import type { ReactNode } from 'react';
import { fmt } from '@/lib/format';

export function PageHeader({ title, description, children }: { title: string; description?: ReactNode; children?: ReactNode }) {
  return (
    <div className="pageHeader">
      <div className="pageTitle">
        <h1>{title}</h1>
        {description && <p className="sub">{description}</p>}
      </div>
      {children && <div className="toolbar">{children}</div>}
    </div>
  );
}

export function Card({ title, aside, children, className }: { title?: ReactNode; aside?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={'card' + (className ? ' ' + className : '')}>
      {(title || aside) && (
        <div className="cardHead">
          {title && <h2>{title}</h2>}
          {aside && <div className="cardAside">{aside}</div>}
        </div>
      )}
      {children}
    </section>
  );
}

/** A message line under the toolbar: the outcome of the last action, or a failure. */
export function Notice({ text, error }: { text: string | null; error?: boolean }) {
  return (
    <p className={'notice' + (error ? ' noticeError' : '')} role="status" aria-live="polite">
      {text || ''}
    </p>
  );
}

export type Level = 'good' | 'warning' | 'critical';

export const level = (ratio: number): Level => (ratio >= 0.9 ? 'critical' : ratio >= 0.75 ? 'warning' : 'good');

const STATES: Record<Level, [string, string]> = {
  good: ['Normal', 'M3 7l3 3 5-6'],
  warning: ['High', 'M7 3v5M7 10.5v.5'],
  critical: ['Critical', 'M4 4l6 6M10 4l-6 6'],
};

/** A status: an icon and a label, never color alone. */
export function State({ level: l, label }: { level: Level; label?: ReactNode }) {
  const [def, path] = STATES[l];
  return (
    <span className={'state state-' + l}>
      <svg width="14" height="14" viewBox="0 0 14 14" aria-hidden="true">
        <circle cx="7" cy="7" r="7" className="stateDot" />
        <path d={path} stroke="#0b0b0b" strokeWidth="1.8" fill="none" strokeLinecap="round" />
      </svg>
      {label ?? def}
    </span>
  );
}

export function Meter({ ratio }: { ratio: number }) {
  const l = level(ratio);
  const width = Math.min(100, Math.max(0, ratio * 100));
  return (
    <div className={'meter meter-' + l} role="meter" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(ratio * 100)}>
      <i style={{ width: width.toFixed(1) + '%' }} />
    </div>
  );
}

export function Delta({ now, before, span }: { now: number; before: number | null | undefined; span: string }) {
  if (before == null) return <span className="flat">{span}: no sample yet</span>;
  const d = now - before;
  const cls = d > 0 ? 'up' : d < 0 ? 'down' : 'flat';
  const sign = d > 0 ? '▲ +' : d < 0 ? '▼ ' : '';
  return (
    <span>
      <span className={cls}>{sign + fmt(d)}</span> <span className="sub">{span}</span>
    </span>
  );
}

export function Tile({ label, value, sub, extra, wide }: { label: ReactNode; value: ReactNode; sub: ReactNode; extra?: ReactNode; wide?: boolean }) {
  return (
    <div className={'card tile' + (wide ? ' tileWide' : '')}>
      <div className="label">{label}</div>
      <div className="value">{value}</div>
      {extra}
      <div className="sub">{sub}</div>
    </div>
  );
}

export function Disabled({ children }: { children: ReactNode }) {
  return <div className="sub disabledLine">{children}</div>;
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="empty">{children}</div>;
}

/** Loading, failure or nothing: the placeholder of a view whose data is not there yet. */
export function LoadState({ loading, error }: { loading: boolean; error: string | null }) {
  if (error) return <div className="card empty errorText">Request failed: {error}</div>;
  if (loading) return <div className="card empty">Loading…</div>;
  return null;
}
