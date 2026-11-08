/* Number and time formats shared by every view. */

const NF = new Intl.NumberFormat('en');
const CF = new Intl.NumberFormat('en', { notation: 'compact', maximumFractionDigits: 1 });

export const DAY = 86_400_000;

export const fmt = (n: number | null | undefined) => (n == null ? '–' : NF.format(n));
export const compact = (n: number | null | undefined) => (n == null ? '–' : CF.format(n));

export function duration(s: number): string {
  const d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60);
  return d ? d + ' d ' + h + ' h' : h ? h + ' h ' + m + ' min' : m + ' min';
}

/** A delay in seconds, short: "12 s", "4 min", "2 h 5 min". */
export const ago = (s: number) => (s < 60 ? Math.round(s) + ' s' : duration(s));

export const clock = (t: number) => new Date(t).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });
export const day = (t: number) => new Date(t).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });
export const stamp = (t: number) => day(t) + ', ' + clock(t);

/** A time sent as epoch milliseconds or as an ISO text; null when absent or unreadable. */
export function toMillis(value: unknown): number | null {
  if (value == null || value === '') return null;
  if (typeof value === 'number') return Number.isFinite(value) && value > 0 ? value : null;
  const parsed = Date.parse(String(value).replace(' ', 'T'));
  return Number.isNaN(parsed) ? null : parsed;
}

export function fullTime(value: unknown): string {
  const t = toMillis(value);
  if (t == null) return value == null ? '' : String(value);
  return new Date(t).toLocaleString('en-GB', {
    day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit',
  });
}

/** "5 min ago" for a past time, the date beyond a week. */
export function relative(value: unknown, now = Date.now()): string {
  const t = toMillis(value);
  if (t == null) return value == null || value === '' ? '–' : String(value);
  const s = Math.max(0, (now - t) / 1000);
  return s < 7 * 86400 ? ago(s) + ' ago' : stamp(t);
}

export const pct = (ratio: number) => (ratio * 100).toFixed(1) + ' %';
