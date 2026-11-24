'use client';

import type { Column } from '@/components/DataTable';

type Row = Record<string, any>;

let names: Intl.DisplayNames | null | undefined;

/** The English name of a country code, or the code itself when the browser cannot name it. */
export function countryName(code: string): string {
  if (names === undefined) {
    try {
      names = new Intl.DisplayNames(['en'], { type: 'region' });
    } catch {
      names = null;
    }
  }
  try {
    return names?.of(code) ?? code;
  } catch {
    return code;
  }
}

/** The flag of a country code: its two regional indicator symbols, the emoji the system draws as the flag. */
export function flagEmoji(code: string): string {
  return String.fromCodePoint(...[...code.toUpperCase()].map((c) => 0x1f1e6 + c.charCodeAt(0) - 65));
}

/** A country: its flag and code, its name in the tooltip; a dash when unknown. */
export function Flag({ code }: { code?: string | null }) {
  if (!code || !/^[A-Za-z]{2}$/.test(code)) return <span className="muted">–</span>;
  const name = countryName(code.toUpperCase());
  return (
    <span className="flag" title={name}>
      <span className="flagEmoji" aria-hidden="true">{flagEmoji(code)}</span>
      <span className="flagCode" aria-label={name}>{code.toUpperCase()}</span>
    </span>
  );
}

/** The Country column of the views listing addresses (country of the address, from the server's database). */
export function countryColumn<R extends Row>(): Column<R> {
  return {
    key: 'country',
    label: 'Country',
    width: '9ch',
    hint: 'Country of the address (IP Geolocation by DB-IP)',
    render: (r) => <Flag code={r.country} />,
    sortValue: (r) => r.country ?? '',
  };
}
