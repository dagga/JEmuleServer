'use client';

import { useState } from 'react';
import { KeyValueTable } from '@/components/DataTable';
import { Card, LoadState, Notice, PageHeader } from '@/components/ui';
import { message, post } from '@/lib/api';
import { fmt } from '@/lib/format';
import { useApi } from '@/lib/hooks';

type Filters = Record<string, any>;

export default function FiltersPage() {
  const { data, error, loading, reload } = useApi<Filters>('/api/filters');
  const [notice, setNotice] = useState<{ text: string; error?: boolean } | null>(null);
  const [busy, setBusy] = useState(false);
  const ranges: string[] = Array.isArray(data?.ipFilterRanges) ? data!.ipFilterRanges : [];

  async function reloadFilter() {
    setBusy(true);
    try {
      const r = await post<{ reloaded: boolean }>('/api/filters/reload');
      setNotice({ text: (r.reloaded ? 'IP filter reload requested.' : 'IP filter path is empty.') + ' Child abuse keywords reloaded.' });
      void reload();
    } catch (e) {
      setNotice({ text: message(e), error: true });
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <PageHeader title="Filters" description="Active IP and fake-file filters.">
        <button type="button" className="btn btnPrimary" disabled={busy} onClick={reloadFilter}>Reload IP filter</button>
      </PageHeader>
      <Notice text={notice?.text ?? null} error={notice?.error} />
      {data ? (
        <>
          <Card><KeyValueTable data={data} skip={['ipFilterRanges']} /></Card>
          <Card title={'Blocked ranges: the first ' + fmt(ranges.length) + ' of ' + fmt(data.ipFilterBlockedRanges)}>
            {ranges.length ? (
              <div className="tableWrap tall">
                <ol className="rangeList">
                  {ranges.map((r, i) => <li key={i} className="mono" title={r}>{r}</li>)}
                </ol>
              </div>
            ) : <div className="empty">No range loaded.</div>}
          </Card>
        </>
      ) : <LoadState loading={loading} error={error} />}
    </>
  );
}
