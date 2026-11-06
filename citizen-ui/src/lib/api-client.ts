import type { Request } from 'express';

export interface ApiContext { requestId?: string; userId?: string; role?: string; resetToken?: string; }
export interface Page<T> { items: T[]; total: number; page: number; size: number; }
export interface ClaimSummary { reference: string; createdAt: string; [key: string]: any; }
export class ApiError extends Error {
  constructor(public status: number, public body: any, public requestId?: string) {
    super(body?.message || `API returned ${status}`);
    this.name = 'ApiError';
  }
}
export function apiContext(req: Request): ApiContext {
  return { requestId: req.requestId, userId: req.session.user?.id, role: req.session.user?.role };
}
export class ApiClient {
  public readonly baseUrl: string;
  constructor(baseUrl: string) {
    // Render's internal hostport references omit the URL scheme.
    this.baseUrl = /^https?:\/\//.test(baseUrl) ? baseUrl : `http://${baseUrl}`;
  }
  private async request(path: string, init: RequestInit, context: ApiContext = {}): Promise<Response> {
    const headers = new Headers(init.headers);
    headers.set('Accept', 'application/json');
    if (context.requestId) headers.set('X-Request-ID', context.requestId);
    if (context.userId) headers.set('X-Demo-User-Id', context.userId);
    if (context.role) headers.set('X-Demo-Role', context.role);
    if (context.resetToken) headers.set('X-Demo-Reset-Token', context.resetToken);
    const response = await fetch(`${this.baseUrl.replace(/\/$/, '')}${path}`, { ...init, headers, signal: AbortSignal.timeout(20000) });
    if (!response.ok) {
      const body = await response.json().catch(() => ({ message: 'The claims service is unavailable' }));
      throw new ApiError(response.status, body, response.headers.get('X-Request-ID') || context.requestId);
    }
    return response;
  }
  private async json<T>(method: string, path: string, body: unknown, context?: ApiContext): Promise<T> {
    const response = await this.request(path, { method, ...(body === undefined ? {} : { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }) }, context);
    return response.status === 204 ? undefined as T : await response.json() as T;
  }
  get<T = any>(path: string, context?: ApiContext): Promise<T> { return this.json('GET', path, undefined, context); }
  post<T = any>(path: string, body?: unknown, context?: ApiContext): Promise<T> { return this.json('POST', path, body, context); }
  put<T = any>(path: string, body?: unknown, context?: ApiContext): Promise<T> { return this.json('PUT', path, body, context); }
  delete<T = any>(path: string, context?: ApiContext): Promise<T> { return this.json('DELETE', path, undefined, context); }
  async upload<T = any>(path: string, file: Blob, filename: string, context?: ApiContext): Promise<T> {
    const form = new FormData();
    form.append('file', file, filename);
    return (await this.request(path, { method: 'POST', body: form }, context)).json() as Promise<T>;
  }
  async download(path: string, context?: ApiContext): Promise<Response> { return this.request(path, { method: 'GET' }, context); }
}
export const civilApi = new ApiClient(process.env.CIVIL_CASE_API_URL || 'http://localhost:8080');
export const legacyApi = new ApiClient(process.env.LEGACY_CLAIM_STORE_URL || 'http://localhost:8081');
export function apiForReference(reference: string): ApiClient { return reference.trim().toUpperCase().startsWith('000LC') ? legacyApi : civilApi; }
export async function getCombinedClaims(query: Record<string, string | number> = {}, context?: ApiContext): Promise<Page<ClaimSummary>> {
  const page = Number(query.page || 0), size = Number(query.size || 20);
  const params = new URLSearchParams(Object.fromEntries(Object.entries({ ...query, page: 0, size: Math.max(250, (page + 1) * size) }).map(([k, v]) => [k, String(v)])));
  const results = await Promise.all([civilApi, legacyApi].map(client => client.get<Page<ClaimSummary>>(`/api/claims?${params}`, context)));
  const items = results.flatMap(result => result.items).sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  return { items: items.slice(page * size, (page + 1) * size), total: results.reduce((n, result) => n + result.total, 0), page, size };
}
