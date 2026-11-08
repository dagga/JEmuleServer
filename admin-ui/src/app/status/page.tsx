'use client';

import { KeyValueTable } from '@/components/DataTable';
import { Card, LoadState, PageHeader } from '@/components/ui';
import { useApi } from '@/lib/hooks';

export default function StatusPage() {
  const { data, error, loading, reload } = useApi<Record<string, unknown>>('/api/status');
  return (
    <>
      <PageHeader title="Status" description="Server state at a glance.">
        <button type="button" className="btn" onClick={() => void reload()}>Refresh</button>
      </PageHeader>
      {data ? <Card><KeyValueTable data={data} /></Card> : <LoadState loading={loading} error={error} />}
    </>
  );
}
