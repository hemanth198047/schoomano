/**
 * Thin API client. The tenant is never sent as a trusted value: X-Schoomano-Tenant only *selects*
 * among schools the signed-in identity already belongs to, and the server re-checks membership.
 */

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly detail: string;
  readonly correlationId: string | null;
  readonly fields: { field: string; message: string }[];
  /** User-readable reasons the server gave, e.g. which term a new term overlaps. */
  readonly problems: string[];

  constructor(
    status: number,
    code: string,
    detail: string,
    correlationId: string | null,
    fields: { field: string; message: string }[] = [],
    problems: string[] = [],
  ) {
    super(detail);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.detail = detail;
    this.correlationId = correlationId;
    this.fields = fields;
    this.problems = problems;
  }
}

export interface ApiSession {
  baseUrl: string;
  token: string;
  tenantSlug?: string;
}

export function newCorrelationId(): string {
  return crypto.randomUUID();
}

export async function apiRequest<T>(
  session: ApiSession,
  path: string,
  init: { method?: string; body?: unknown; form?: FormData; signal?: AbortSignal } = {},
): Promise<T> {
  const headers: Record<string, string> = {
    Accept: 'application/json',
    Authorization: `Bearer ${session.token}`,
    'X-Correlation-Id': newCorrelationId(),
  };
  if (session.tenantSlug) headers['X-Schoomano-Tenant'] = session.tenantSlug;
  // A FormData body (file upload) sets its own multipart Content-Type with the boundary.
  if (init.body !== undefined && init.form === undefined) headers['Content-Type'] = 'application/json';

  let res: Response;
  try {
    res = await fetch(`${session.baseUrl}${path}`, {
      method: init.method ?? 'GET',
      headers,
      body: init.form ?? (init.body === undefined ? undefined : JSON.stringify(init.body)),
      signal: init.signal,
    });
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') throw e;
    throw new ApiError(
      0,
      'network_unreachable',
      'The server could not be reached. Check your connection.',
      null,
    );
  }

  const correlationId = res.headers.get('X-Correlation-Id');
  if (res.ok) {
    return (res.status === 204 ? undefined : await res.json()) as T;
  }
  let problem: {
    code?: string;
    detail?: string;
    correlationId?: string;
    fields?: ApiError['fields'];
    problems?: string[];
  } = {};
  try {
    problem = await res.json();
  } catch {
    /* non-JSON error body: fall through to a generic message */
  }
  throw new ApiError(
    res.status,
    problem.code ?? (res.status === 401 ? 'unauthenticated' : 'http_error'),
    problem.detail ?? `Request failed with status ${res.status}.`,
    problem.correlationId ?? correlationId,
    problem.fields ?? [],
    problem.problems ?? [],
  );
}
