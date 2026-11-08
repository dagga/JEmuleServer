/* JSON API of the admin HTTP server, always on the page's own origin. */

export class ApiError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
  }
}

async function body(response: Response): Promise<any> {
  const text = await response.text();
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

export async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(path, { cache: 'no-store', headers: { Accept: 'application/json' } });
  const data = await body(response);
  if (!response.ok) throw new ApiError(data?.error || response.statusText || 'HTTP ' + response.status, response.status);
  return data as T;
}

/**
 * Same-origin POST of form-encoded data: the browser adds the Origin header the server checks.
 */
export async function post<T = any>(path: string, data: Record<string, string> = {}): Promise<T> {
  const response = await fetch(path, {
    method: 'POST',
    cache: 'no-store',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(data),
  });
  const result = await body(response);
  if (!response.ok) {
    const message = result?.error || (Array.isArray(result?.errors) && result.errors.join('; ')) || response.statusText;
    throw new ApiError(message || 'HTTP ' + response.status, response.status);
  }
  return result as T;
}

export const isNotFound = (e: unknown) => e instanceof ApiError && e.status === 404;

export const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

/** Result of a settings change or reload. */
export interface ConfigResult {
  success?: boolean;
  applied?: string[];
  deferred?: string[];
  errors?: string[];
}

export function summarize(r: ConfigResult): string {
  const parts: string[] = [];
  if (r.applied?.length) parts.push('applied: ' + r.applied.join(', '));
  if (r.deferred?.length) parts.push('restart required: ' + r.deferred.join(', '));
  if (r.errors?.length) parts.push('errors: ' + r.errors.join('; '));
  return parts.join(' | ') || 'No change.';
}
