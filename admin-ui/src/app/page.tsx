'use client';

import { useCallback, useEffect, useState } from 'react';
import { DailyTable, LineChart, type Point } from '@/components/LineChart';
import { useMeta } from '@/components/Shell';
import { Delta, Disabled, Meter, Notice, PageHeader, State, Tile, level } from '@/components/ui';
import { getJson, message } from '@/lib/api';
import { DAY, ago, clock, compact, duration, fmt, pct, stamp } from '@/lib/format';
import { useInterval } from '@/lib/hooks';

type Obj = Record<string, any>;

interface History { intervalSeconds: number; points: Point[] }

const RANGES: [number, string][] = [[1, '24 h'], [7, '7 days'], [30, '30 days']];
const FEED_QUEUE_MAX = 200_000;

// The range chosen stays while the panel is open
let savedRange = 30;

export default function OverviewPage() {
  const { setText } = useMeta();
  const [o, setO] = useState<Obj | null>(null);
  const [hist, setHist] = useState<History | null>(null);
  const [range, setRange] = useState(savedRange);
  const [notice, setNotice] = useState<string | null>(null);

  const refreshKpis = useCallback(async () => {
    try {
      const data = await getJson<Obj>('/api/overview');
      setO(data);
      setNotice(null);
      setText('v' + data.version + ' · up ' + duration(data.uptimeSeconds) + ' · updated ' + clock(Date.now()));
    } catch (e) {
      setNotice('Request failed: ' + message(e));
    }
  }, [setText]);

  const refreshHistory = useCallback(async () => {
    try {
      setHist(await getJson<History>('/api/history?days=30'));
    } catch (e) {
      setNotice('Request failed: ' + message(e));
    }
  }, []);

  useEffect(() => {
    void refreshKpis();
    void refreshHistory();
  }, [refreshKpis, refreshHistory]);
  // Headline figures every 30 s, curves every 5 minutes (the sampling interval)
  useInterval(refreshKpis, 30_000);
  useInterval(refreshHistory, 300_000);

  function choose(days: number) {
    savedRange = days;
    setRange(days);
  }

  const all = hist?.points ?? [];
  const from = Date.now() - range * DAY;
  const pts = all.filter((p) => p[0] >= from);
  const interval = (hist?.intervalSeconds ?? 300) * 1000;

  return (
    <>
      <PageHeader title="Overview" description="Figures refreshed every 30 seconds, curves every 5 minutes." />
      <Notice text={notice} error />
      {o ? <Figures o={o} /> : !notice && <div className="card empty">Loading…</div>}

      <div className="range">
        <span className="label">Range</span>
        <div className="seg" role="group" aria-label="Range of the curves">
          {RANGES.map(([d, l]) => (
            <button key={d} type="button" aria-pressed={d === range} onClick={() => choose(d)}>{l}</button>
          ))}
        </div>
        <span className="sub">{all.length ? fmt(all.length) + ' samples since ' + stamp(all[0][0]) : ''}</span>
      </div>
      <div className="grid charts">
        <ChartCard title="Indexed files" desc="Files known to the index" pts={pts} k={1} series={1} interval={interval} ready={!!hist} />
        <ChartCard title="Connected clients" desc="eD2K clients connected to this server" pts={pts} k={2} series={2} interval={interval} ready={!!hist} />
      </div>
    </>
  );
}

function ChartCard({ title, desc, pts, k, series, interval, ready }: {
  title: string; desc: string; pts: Point[]; k: number; series: 1 | 2; interval: number; ready: boolean;
}) {
  return (
    <section className="card">
      <h2 className="chartTitle">{title}</h2>
      <div className="sub">{desc}, sampled every 5 minutes</div>
      {ready ? <LineChart points={pts} k={k} series={series} name={title} interval={interval} /> : <div className="plot"><div className="empty">Loading…</div></div>}
      <details>
        <summary>Show as table</summary>
        <DailyTable points={pts} k={k} name={title} />
      </details>
    </section>
  );
}

function Figures({ o }: { o: Obj }) {
  const f: Obj = o.files ?? {}, c: Obj = o.clients ?? {}, m: Obj = o.memory ?? {}, s: Obj = o.searches ?? {};
  const v: Obj = o.offerFilesV1 ?? {}, k: Obj = o.kadSources ?? {}, ss: Obj = o.serverSources ?? {};
  const iu: Obj = o.serverIndexUpdate ?? {}, r: Obj = o.relay ?? {}, rf: Obj = o.relayFeed ?? {}, rr: Obj = o.relayFeedReceived ?? {};
  const protection: Obj = o.protection ?? {}, database: Obj = o.database ?? {};

  // The index is full at maxFiles or when the live heap reaches the guard, whichever comes first
  const fileFill = f.max ? f.local / f.max : 0, heapFill = m.guardMb ? m.liveOldMb / m.guardMb : 0;
  const fill = Math.min(1, Math.max(fileFill, heapFill)), byHeap = heapFill > fileFill;
  const heap = m.heapMaxMb ? m.heapUsedMb / m.heapMaxMb : 0;
  const lowShare = c.connected ? Math.round((c.lowId * 100) / c.connected) : 0;

  return (
    <>
      <div className="grid hero">
        <section className="card heroCard">
          <div className="label">{f.relayMode ? 'Files of the relay server' : 'Indexed files'}</div>
          <div className="big">{fmt(f.indexed)}</div>
          <div className="deltas">
            <Delta now={f.indexed} before={f.ago24h} span="in 24 h" />
            <Delta now={f.indexed} before={f.ago7d} span="in 7 days" />
          </div>
          <div className="sub spaced">
            {f.relayMode ? (
              <>
                the index of {r.server || ''} answers the searches
                {f.relayIndexError
                  ? <> · <b>its figures are not reachable: {f.relayIndexError}</b></>
                  : f.relayIndexAgeSeconds != null ? ' · figures of ' + ago(f.relayIndexAgeSeconds) + ' ago' : ''}
                {' · local fallback index: ' + fmt(f.local) + ' files'}
              </>
            ) : (
              fmt(f.newSinceStart) + ' new files since the last start'
            )}
          </div>
        </section>
        {f.relayMode ? <FeedHero rf={rf} /> : (
          <section className="card heroCard">
            <div className="label">Index capacity · limited by {byHeap ? 'the heap' : 'maxFiles'}</div>
            <div className="value">{pct(fill)}</div>
            <Meter ratio={fill} />
            <State
              level={m.heapFull || fileFill >= 1 ? 'critical' : level(fill)}
              label={m.heapFull ? 'Full: heap' : fileFill >= 1 ? 'Full: maxFiles' : fill >= 0.9 ? 'Near the limit' : fill >= 0.75 ? 'Filling up' : 'Room to grow'}
            />
            <div className="sub spaced">
              {'maxFiles: ' + fmt(f.indexed) + ' of ' + fmt(f.max) + ' files (' + pct(fileFill) + ')'}
              {m.guardMb ? ' · heap guard: ' + fmt(m.liveOldMb) + ' of ' + fmt(m.guardMb) + ' MB of live heap (' + pct(heapFill) + ')' : ''}
              {' · ' + fmt(f.refusedFull || 0) + ' new files refused since the last start; known files still get sources'}
            </div>
          </section>
        )}
      </div>

      <div className="grid kpis">
        <Tile label="Connected clients" value={fmt(c.connected)} sub={fmt(c.lowId) + ' LowID (' + lowShare + ' %) · max ' + fmt(c.max)} />
        <Tile
          label="Heap"
          value={fmt(m.heapUsedMb) + ' MB'}
          extra={<Meter ratio={heap} />}
          sub={'of ' + fmt(m.heapMaxMb) + ' MB, including uncollected garbage' + (m.systemLoad != null ? ' · load ' + Number(m.systemLoad).toFixed(2) : '')}
        />
        <Tile label="Peer eD2K servers" value={fmt(o.servers?.verified)} sub={'verified of ' + fmt(o.servers?.known) + ' known'} />
        <Tile label="Remote searches" value={fmt((s.kad ?? 0) + (s.ed2kServers ?? 0))} sub={<RemoteSearches s={s} />} />
        <Tile
          label="Files found by searches"
          value={fmt(s.filesIngested)}
          sub={f.relayMode ? 'since the last start, sent to the relay server' : 'indexed since the last start'}
        />
        <Tile
          label="Kad and server sources for clients"
          value={fmt(k.sourcesDelivered || 0)}
          extra={k.enabled ? <State level="good" label="Enabled" /> : <Disabled>Disabled (kadSourceSearchEnabled)</Disabled>}
          sub={'sent in ' + fmt(k.answersSent || 0) + ' answers · ' + fmt(k.searches || 0) + ' searches, ' + fmt(k.cacheHits || 0)
            + ' from cache · ' + fmt(ss.highIdSources || 0) + ' HighID sources from other servers · ' + fmt(k.firewalledMatched || 0)
            + ' firewalled Kad sources reachable through this server · quota ' + fmt(k.quota || 0) + ' of ' + fmt(k.quotaMax || 0)
            + ' searches a minute (' + fmt(k.kadRefusals || 0) + ' Kad refusals)'}
        />
        <Tile
          label="Index update from eD2K index peers"
          value={fmt(iu.newFiles || 0)}
          extra={iu.enabled ? <State level="good" label="Enabled" /> : <Disabled>Disabled (serverIndexUpdateEnabled)</Disabled>}
          sub={'new files since the last start · ' + fmt(iu.searches || 0) + ' searches (' + fmt(iu.refinements || 0) + ' refinements, '
            + fmt(iu.fullPages || 0) + ' full pages) on ' + fmt(iu.servers || 0) + ' peers · ' + fmt(iu.accepted || 0) + ' of '
            + fmt(iu.results || 0) + ' results kept · ' + fmt(iu.incidents || 0) + ' pauses after an incident'}
        />
        <Tile
          label="Relay server"
          value={fmt(r.searches || 0)}
          extra={r.active ? <State level="good" label="Relay mode: this index is only a fallback" /> : <Disabled>Not configured (relayServer)</Disabled>}
          sub={'searches played on ' + (r.server || 'no relay server')
            + (r.announcedFiles != null ? ' · ' + fmt(r.announcedFiles) + ' files announced to the clients' : '')
            + ' · ' + fmt(r.results || 0) + ' results in ' + fmt(r.pages || 0) + ' pages · ' + fmt(r.sourcesFound || 0)
            + ' HighID sources for ' + fmt(r.sourceRequests || 0) + ' requests · ' + fmt(r.announcedLogins || 0)
            + ' logins told of it · ' + fmt(r.failures || 0) + ' failures'}
        />
        {rr.clients ? (
          <Tile
            label="Files from relay servers"
            value={fmt(rr.newFiles || 0)}
            sub={'new files indexed since the last start · ' + fmt(rr.files || 0) + ' received in ' + fmt(rr.batches || 0)
              + ' batches from ' + rr.clients + ' · ' + fmt(rr.rejected || 0) + ' rejected by the filters · ' + fmt(rr.denied || 0)
              + ' refused posts · ' + fmt(rr.busy || 0) + ' deferred (ingestion busy)' + (rr.lastFrom ? ' · last from ' + rr.lastFrom : '')}
          />
        ) : null}
        <Tile
          label="OFFERFILES v1"
          value={fmt(v.newFiles)}
          extra={v.enabled ? <State level="good" label="Enabled" /> : <Disabled>Disabled (offerFilesV1Enabled)</Disabled>}
          sub={'new files from fast-publishing clients since the last start · ' + fmt(v.newFilesLegacy) + ' from legacy clients · '
            + fmt(v.sessionsAdvertised) + ' of ' + fmt(v.loginsAsked) + ' asking logins answered'}
        />
        <Tile
          label="Banned names"
          value={compact(protection.bannedNames)}
          sub={fmt(protection.bannedNames) + ' file names, from the administrator and the child abuse filter · '
            + fmt(protection.childAbuseRejected) + ' files and ' + fmt(protection.childAbuseQueries || 0)
            + ' searches refused by the child abuse filter since the last start · ' + fmt(protection.ipFilterRanges) + ' IP filter ranges'}
        />
        <Tile
          label={'Database' + (database.type ? ' (' + database.type + ')' : '')}
          value={database.sizeMb == null ? '–' : fmt(database.sizeMb) + ' MB'}
          sub={database.reopens
            ? <State level="critical" label={(database.type === 'MySQL' ? 'Reconnected ' : 'Reopened ') + database.reopens + ' times after a fatal error'} />
            : <State level="good" label="No fatal error" />}
        />
        <Tile label="Uptime" value={duration(o.uptimeSeconds)} sub={'version ' + o.version} />
      </div>
    </>
  );
}

/** Relay mode: the files sent to the relay server take the place of the index capacity. */
function FeedHero({ rf }: { rf: Obj }) {
  const since = rf.lastSentAt ? Math.round((Date.now() - rf.lastSentAt) / 1000) : null;
  const fill = Math.min(1, (rf.queued || 0) / FEED_QUEUE_MAX);
  return (
    <section className="card heroCard">
      <div className="label">Files sent to the relay server</div>
      <div className="value">{fmt(rf.sent || 0)}</div>
      <Meter ratio={fill} />
      {!rf.target ? <State level="critical" label="No feed: relayFeedPort or relayToken missing" />
        : rf.lastError ? <State level="warning" label={'Failing: ' + rf.lastError} />
          : <State level={level(fill)} label={since == null ? 'Nothing sent yet' : 'Last batch ' + ago(since) + ' ago'} />}
      <div className="sub spaced">
        {fmt(rf.queued || 0) + ' waiting of ' + fmt(FEED_QUEUE_MAX) + ' at most · ' + fmt(rf.batches || 0) + ' batches · '
          + fmt(rf.offered || 0) + ' files found, ' + fmt(rf.alreadyQueued || 0) + ' already sent with as many sources · '
          + fmt(rf.dropped || 0) + ' lost while the relay server was away · ' + fmt(rf.failures || 0) + ' failed posts'}
      </div>
    </section>
  );
}

/** Searches sent to Kad and to the servers, with the cache figures of servers that report them. */
function RemoteSearches({ s }: { s: Obj }) {
  const parts = ['Kad ' + fmt(s.kad) + ' · eD2K servers ' + fmt(s.ed2kServers) + ' · UDP global received ' + fmt(s.udpGlobal)];
  if (s.kadWordSearches != null) parts.push(fmt(s.kadWordSearches) + ' Kad keyword searches');
  for (const [prefix, name] of [['kad', 'Kad'], ['server', 'server']] as const) {
    const figures: string[] = [];
    for (const [key, label] of [['Hits', 'hits'], ['Misses', 'misses'], ['Joins', 'joins']] as const) {
      const value = s[prefix + 'Cache' + key];
      if (value != null) figures.push(fmt(value) + ' ' + label);
    }
    if (figures.length) parts.push(name + ' cache: ' + figures.join(', '));
  }
  return <>{parts.join(' · ')}</>;
}
