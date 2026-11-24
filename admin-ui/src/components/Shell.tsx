'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { getJson, isNotFound } from '@/lib/api';
import { useHash } from '@/lib/hooks';

/** A group of settings: one entry of the Settings sub-menu. */
export interface SettingsSection {
  id: string;
  label: string;
  /** Number of parameters in the group. */
  count: number;
}

/** The groups of settings in the order the server lists its parameters (grouped by ConfigParameter.Category). */
export function sectionsOf(parameters: { category?: string; categoryLabel?: string }[]): SettingsSection[] {
  const sections: SettingsSection[] = [];
  for (const p of parameters) {
    if (!p.category) continue;
    const section = sections.find((s) => s.id === p.category);
    if (section) section.count++;
    else sections.push({ id: p.category, label: p.categoryLabel || p.category, count: 1 });
  }
  return sections;
}

interface Meta {
  text: string;
  setText: (text: string) => void;
}

const MetaContext = createContext<Meta>({ text: '', setText: () => {} });

/** The version, uptime and time of the last refresh, shown in the header by the Overview. */
export const useMeta = () => useContext(MetaContext);

/** Line icons of the views, 20 × 20, drawn with the text color. */
const ICONS: Record<string, string> = {
  '/': 'M3 3h6v6H3zM11 3h6v6h-6zM3 11h6v6H3zM11 11h6v6h-6z',
  '/status/': 'M2 10h4l2-5 4 10 2-5h4',
  '/clients/': 'M7 9a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM1.5 17c0-3 2.5-5 5.5-5s5.5 2 5.5 5M14 3.4a3 3 0 0 1 0 5.6M15 12.3c2 .5 3.5 2.3 3.5 4.7',
  '/peers/': 'M3 10a2 2 0 1 0 4 0 2 2 0 1 0-4 0M13 5a2 2 0 1 0 4 0 2 2 0 1 0-4 0M13 15a2 2 0 1 0 4 0 2 2 0 1 0-4 0M6.8 9.1l6.4-3.2M6.8 10.9l6.4 3.2',
  '/servers/': 'M3 3.5h14v5H3zM3 11.5h14v5H3zM6 6h.01M6 14h.01',
  '/ingestion/': 'M10 2.5v9M6 8l4 4 4-4M3 13.5v3.5h14v-3.5',
  '/searches/': 'M3 8.5a5.5 5.5 0 1 0 11 0 5.5 5.5 0 1 0-11 0M12.5 12.5L17.5 17.5',
  '/filters/': 'M2.5 4h15l-6 7v5.5l-3 1.5V11z',
  '/bots/': 'M4.5 7h11v9h-11zM10 3v4M7.5 11h.01M12.5 11h.01M7.5 13.5h5M2.5 10v3M17.5 10v3',
  '/blocks/': 'M3 10a7 7 0 1 0 14 0 7 7 0 1 0-14 0M5 5l10 10',
  '/namecleaning/': 'M9 2l1.6 4.4L15 8l-4.4 1.6L9 14l-1.6-4.4L3 8l4.4-1.6zM15 12l.8 2.2L18 15l-2.2.8L15 18l-.8-2.2L12 15l2.2-.8z',
  '/settings/': 'M3 5h14M3 10h14M3 15h14M7 3v4M13 8v4M9 13v4',
};

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

function Icon({ path }: { path: string }) {
  return (
    <svg className="navIcon" width="18" height="18" viewBox="0 0 20 20" aria-hidden="true">
      <path d={path} fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

const SETTINGS = '/settings/';

const normalize = (path: string) => (path.endsWith('/') ? path : path + '/');

export function Shell({ children }: { children: ReactNode }) {
  const pathname = normalize(usePathname() || '/');
  const [text, setText] = useState('');
  // Views of features a server may not have: hidden when their API answers 404
  const [missing, setMissing] = useState<Set<string>>(new Set());
  // The Settings sub-menu, read once the Settings view is opened
  const [sections, setSections] = useState<SettingsSection[]>([]);
  const hash = useHash();
  const onSettings = pathname === SETTINGS;

  useEffect(() => {
    for (const view of VIEWS) {
      if (!view.optional) continue;
      getJson(view.optional).catch((e) => {
        if (isNotFound(e)) setMissing((m) => new Set(m).add(view.href));
      });
    }
  }, []);

  useEffect(() => {
    if (!onSettings || sections.length) return;
    getJson<{ category?: string; categoryLabel?: string }[]>('/api/settings')
      .then((parameters) => setSections(sectionsOf(parameters)))
      .catch(() => {}); // the view shows the error
  }, [onSettings, sections.length]);

  return (
    <MetaContext.Provider value={{ text, setText }}>
      <div className="app">
        <aside className="side">
          <div className="brand">
            {/* The JEmuleServer logo: the mule of eMule and the blue bubble of its "e", the server and its peers */}
            {/* eslint-disable-next-line @next/next/no-img-element -- a static SVG of the export, nothing to optimize */}
            <img className="logo" src="/jemuleserver_icone.svg" alt="" width={46} height={46} />
            <div>
              <div className="brandName" aria-label="JEmuleServer">
                <span className="brandStrong">JEmule</span><span className="brandThin">Server</span>
              </div>
              <div className="meta" title="Version, uptime and last refresh">
                {text && <span className="liveDot" aria-hidden="true" />}
                {text || 'admin'}
              </div>
            </div>
          </div>
          <nav aria-label="Views">
            {VIEWS.filter((v) => !missing.has(v.href)).map((v) => (
              <div key={v.href} className="navItem">
                <Link href={v.href} className="navLink" aria-current={pathname === v.href ? 'page' : undefined}>
                  <Icon path={ICONS[v.href] ?? ICONS['/']} />
                  <span>{v.label}</span>
                </Link>
                {v.href === SETTINGS && onSettings && sections.length > 0 && (
                  <div className="navSub" role="group" aria-label="Groups of settings">
                    <a href="#" className="navSubLink" aria-current={hash === '' ? 'location' : undefined}>
                      <span>All settings</span>
                      <span className="navCount">{sections.reduce((n, s) => n + s.count, 0)}</span>
                    </a>
                    {sections.map((s) => (
                      <a key={s.id} href={'#' + s.id} className="navSubLink" aria-current={hash === s.id ? 'location' : undefined}>
                        <span>{s.label}</span>
                        <span className="navCount">{s.count}</span>
                      </a>
                    ))}
                  </div>
                )}
              </div>
            ))}
          </nav>
          <p className="sideNote">Administration only. Potential bots are identified by nickname heuristic, not verified.</p>
        </aside>
        <main className="main">{children}</main>
      </div>
    </MetaContext.Provider>
  );
}
