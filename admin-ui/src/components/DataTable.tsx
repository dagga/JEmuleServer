'use client';

import { useMemo, useState, type CSSProperties, type ReactNode } from 'react';
import { ago, duration, fmt, fullTime, relative, toMillis } from '@/lib/format';

/**
 * How a value is shown: text and code are truncated with an ellipsis (full value on hover), num gets thousands
 * separators, int is a number kept as is (ports, versions), ago and seconds are durations in seconds, time is a date.
 */
export type Kind = 'text' | 'code' | 'num' | 'int' | 'pct' | 'bool' | 'time' | 'ago' | 'seconds';

export interface Column<R> {
  key: string;
  label: string;
  kind?: Kind;
  /** Widest a truncated cell gets, as a CSS length. */
  width?: string;
  /** Header tooltip. */
  hint?: string;
  render?: (row: R) => ReactNode;
  sortValue?: (row: R) => unknown;
}

type Row = Record<string, any>;

const NUMERIC: Kind[] = ['num', 'int', 'pct', 'ago', 'seconds'];

export function Trunc({ text, width, code }: { text: string; width?: string; code?: boolean }) {
  const style = width ? ({ '--w': width } as CSSProperties) : undefined;
  return (
    <span className={'trunc' + (code ? ' mono' : '')} title={text} style={style}>
      {text}
    </span>
  );
}

function cell<R extends Row>(column: Column<R>, row: R): ReactNode {
  if (column.render) return column.render(row);
  const v = row[column.key];
  if (v == null || v === '') return <span className="muted">–</span>;
  switch (column.kind) {
    case 'num':
      return typeof v === 'number' ? fmt(v) : String(v);
    case 'int':
      return String(v);
    case 'pct':
      return typeof v === 'number' ? v.toFixed(1) + ' %' : String(v);
    case 'bool':
      return v === true ? 'yes' : v === false ? 'no' : String(v);
    case 'time':
      return <span title={fullTime(v)}>{relative(v)}</span>;
    case 'ago':
      return <span title={fmt(v) + ' s'}>{ago(v)} ago</span>;
    case 'seconds':
      return <span title={fmt(v) + ' s'}>{v < 60 ? v + ' s' : duration(v)}</span>;
    case 'code':
      return <Trunc text={String(v)} width={column.width} code />;
    default:
      return <Trunc text={typeof v === 'object' ? JSON.stringify(v) : String(v)} width={column.width} />;
  }
}

function sortKey<R extends Row>(column: Column<R>, row: R): unknown {
  if (column.sortValue) return column.sortValue(row);
  const v = row[column.key];
  if (column.kind === 'time') return toMillis(v);
  return v;
}

function compare(a: unknown, b: unknown): number {
  if (a == null || a === '') return b == null || b === '' ? 0 : 1;
  if (b == null || b === '') return -1;
  if (typeof a === 'number' && typeof b === 'number') return a - b;
  if (typeof a === 'boolean' && typeof b === 'boolean') return Number(a) - Number(b);
  return String(a).localeCompare(String(b), 'en', { numeric: true, sensitivity: 'base' });
}

/**
 * Columns for the fields a view does not know yet (a newer server): numbers when every value is one, except ports and
 * identifiers, text otherwise.
 */
export function withExtraColumns<R extends Row>(rows: R[], columns: Column<R>[]): Column<R>[] {
  if (!rows.length) return columns;
  const known = new Set(columns.map((c) => c.key));
  const extra: Column<R>[] = [];
  for (const key of Object.keys(rows[0])) {
    if (known.has(key)) continue;
    const numeric = rows.every((r) => r[key] == null || typeof r[key] === 'number');
    const bool = rows.every((r) => r[key] == null || typeof r[key] === 'boolean');
    extra.push({ key, label: key, kind: bool ? 'bool' : numeric ? (/port|Id|Port/.test(key) ? 'int' : 'num') : 'text' });
  }
  return columns.concat(extra);
}

export function DataTable<R extends Row>({
  rows, columns, rowKey, actions, empty = 'No entries.', rowClass, sortable = true, tall,
}: {
  rows: R[];
  columns: Column<R>[];
  rowKey?: (row: R, index: number) => string;
  actions?: (row: R) => ReactNode;
  empty?: string;
  rowClass?: (row: R) => string | undefined;
  sortable?: boolean;
  tall?: boolean;
}) {
  const [sort, setSort] = useState<{ key: string; dir: 1 | -1 } | null>(null);
  const sorted = useMemo(() => {
    if (!sort) return rows;
    const column = columns.find((c) => c.key === sort.key);
    if (!column) return rows;
    return rows
      .map((row, i) => ({ row, i }))
      .sort((a, b) => {
        const va = sortKey(column, a.row), vb = sortKey(column, b.row);
        // Empty values last in both directions
        if (va == null || va === '' || vb == null || vb === '') return compare(va, vb) || a.i - b.i;
        return sort.dir * compare(va, vb) || a.i - b.i;
      })
      .map((x) => x.row);
  }, [rows, columns, sort]);

  if (!rows.length) return <div className="empty">{empty}</div>;

  function toggle(key: string) {
    setSort((s) => (s?.key !== key ? { key, dir: 1 } : s.dir === 1 ? { key, dir: -1 } : null));
  }

  return (
    <div className={'tableWrap' + (tall ? ' tall' : '')}>
      <table>
        <thead>
          <tr>
            {columns.map((c) => {
              const numeric = NUMERIC.includes(c.kind ?? 'text');
              const state = sort?.key === c.key ? (sort.dir === 1 ? 'ascending' : 'descending') : 'none';
              return (
                <th key={c.key} className={numeric ? 'num' : undefined} aria-sort={sortable ? state : undefined} title={c.hint}>
                  {sortable ? (
                    <button type="button" className="sortButton" onClick={() => toggle(c.key)}>
                      {c.label}
                      <span className="sortMark" aria-hidden="true">{state === 'ascending' ? '▲' : state === 'descending' ? '▼' : ''}</span>
                    </button>
                  ) : (
                    c.label
                  )}
                </th>
              );
            })}
            {actions && <th className="actionsCell"><span className="visuallyHidden">Actions</span></th>}
          </tr>
        </thead>
        <tbody>
          {sorted.map((row, i) => (
            <tr key={rowKey ? rowKey(row, i) : i} className={rowClass?.(row)}>
              {columns.map((c) => (
                <td key={c.key} className={NUMERIC.includes(c.kind ?? 'text') ? 'num' : undefined}>
                  {cell(c, row)}
                </td>
              ))}
              {actions && <td className="actionsCell">{actions(row)}</td>}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Name and value pairs of an object, as the Status and Filters views show them. */
export function KeyValueTable({ data, skip = [] }: { data: Row; skip?: string[] }) {
  const entries = Object.entries(data).filter(([k]) => !skip.includes(k));
  return (
    <div className="tableWrap">
      <table className="kv">
        <tbody>
          {entries.map(([k, v]) => (
            <tr key={k}>
              <th scope="row">{k}</th>
              <td className={typeof v === 'number' ? 'num' : undefined}>
                {typeof v === 'number' ? fmt(v) : typeof v === 'boolean' ? String(v)
                  : Array.isArray(v) ? v.join(', ') : v == null ? '–' : typeof v === 'object' ? JSON.stringify(v) : String(v)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** A filter box over a table's rows: every value of a row is searched. */
export function useRowFilter<R extends Row>(rows: R[], text: string): R[] {
  return useMemo(() => {
    const q = text.trim().toLowerCase();
    if (!q) return rows;
    return rows.filter((r) => Object.values(r).some((v) => v != null && String(v).toLowerCase().includes(q)));
  }, [rows, text]);
}
