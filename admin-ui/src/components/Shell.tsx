'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { getJson, isNotFound } from '@/lib/api';

interface Meta {
  text: string;
  setText: (text: string) => void;
}

const MetaContext = createContext<Meta>({ text: '', setText: () => {} });

/** The version, uptime and time of the last refresh, shown in the header by the Overview. */
export const useMeta = () => useContext(MetaContext);

const VIEWS: { href: string; label: string; optional?: string }[] = [
  { href: '/', label: 'Overview' },
  { href: '/status/', label: 'Status' },
  { href: '/clients/', label: 'Clients' },
  { href: '/peers/', label: 'Peers' },
  { href: '/servers/', label: 'Servers' },
  { href: '/ingestion/', label: 'Ingestion' },
  { href: '/searches/', label: 'Searches' },
  { href: '/filters/', label: 'Filters' },
  { href: '/bots/', label: 'Bots' },
  { href: '/blocks/', label: 'Blocks' },
  { href: '/namecleaning/', label: 'Name cleaning', optional: '/api/namecleaning' },
  { href: '/settings/', label: 'Settings' },
];

const normalize = (path: string) => (path.endsWith('/') ? path : path + '/');

export function Shell({ children }: { children: ReactNode }) {
  const pathname = normalize(usePathname() || '/');
  const [text, setText] = useState('');
  // Views of features a server may not have: hidden when their API answers 404
  const [missing, setMissing] = useState<Set<string>>(new Set());

  useEffect(() => {
    for (const view of VIEWS) {
      if (!view.optional) continue;
      getJson(view.optional).catch((e) => {
        if (isNotFound(e)) setMissing((m) => new Set(m).add(view.href));
      });
    }
  }, []);

  return (
    <MetaContext.Provider value={{ text, setText }}>
      <div className="app">
        <aside className="side">
          <div className="brand">
            <svg width="26" height="26" viewBox="0 0 32 32" aria-hidden="true" className="logo">
              <rect width="32" height="32" rx="8" />
              <path d="M9 21.5c2.6-6.5 5.5-11 7-11s4.4 4.5 7 11" fill="none" strokeWidth="2.6" strokeLinecap="round" />
              <circle cx="16" cy="10.5" r="2.6" />
            </svg>
            <div>
              <div className="brandName">JEmuleServer</div>
              <div className="meta" title="Version, uptime and last refresh">{text || 'admin'}</div>
            </div>
          </div>
          <nav aria-label="Views">
            {VIEWS.filter((v) => !missing.has(v.href)).map((v) => (
              <Link key={v.href} href={v.href} className="navLink" aria-current={pathname === v.href ? 'page' : undefined}>
                {v.label}
              </Link>
            ))}
          </nav>
          <p className="sideNote">Administration only. Potential bots are identified by nickname heuristic, not verified.</p>
        </aside>
        <main className="main">{children}</main>
      </div>
    </MetaContext.Provider>
  );
}
