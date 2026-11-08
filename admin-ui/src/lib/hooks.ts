'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { getJson, message } from './api';

export interface Loaded<T> {
  data: T | null;
  error: string | null;
  status: number | null;
  loading: boolean;
  reload: () => Promise<void>;
}

/** GET of a JSON resource, again on reload(); the previous data stays shown while a reload runs. */
export function useApi<T>(path: string | null): Loaded<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const latest = useRef(path);
  latest.current = path;

  const reload = useCallback(async () => {
    if (path == null) return;
    setLoading(true);
    try {
      const result = await getJson<T>(path);
      if (latest.current !== path) return;
      setData(result);
      setError(null);
      setStatus(200);
    } catch (e) {
      if (latest.current !== path) return;
      setError(message(e));
      setStatus((e as { status?: number }).status ?? null);
    } finally {
      if (latest.current === path) setLoading(false);
    }
  }, [path]);

  useEffect(() => {
    void reload();
  }, [reload]);

  return { data, error, status, loading, reload };
}

/** Calls fn every delay ms while delay is not null. */
export function useInterval(fn: () => void, delay: number | null) {
  const saved = useRef(fn);
  saved.current = fn;
  useEffect(() => {
    if (delay == null) return;
    const id = setInterval(() => saved.current(), delay);
    return () => clearInterval(id);
  }, [delay]);
}

/** Width of an element, followed as it changes. */
export function useWidth<E extends HTMLElement>(): [React.RefObject<E | null>, number] {
  const ref = useRef<E>(null);
  const [width, setWidth] = useState(0);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    setWidth(el.clientWidth);
    const observer = new ResizeObserver(() => setWidth(el.clientWidth));
    observer.observe(el);
    return () => observer.disconnect();
  }, []);
  return [ref, width];
}
