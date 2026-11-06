import { describe, it, expect } from 'vitest';
import request from 'supertest';
import { createApp } from './app';

describe('shared citizen shell', () => {
  it('renders demo branding without government logos', async () => {
    const res = await request(createApp()).get('/');
    expect(res.status).toBe(200);
    expect(res.text).toContain('HMCTS demo');
    expect(res.text).toContain('this is not a real government service');
    expect(res.text).not.toMatch(/govuk-header__logotype|govuk-footer__copyright-logo/);
    expect(res.headers['x-request-id']).toBeTruthy();
  });
  it('propagates safe request IDs', async () => {
    const res = await request(createApp()).get('/health').set('X-Request-ID', 'smoke-request-123');
    expect(res.body.status).toBe('UP');
    expect(res.headers['x-request-id']).toBe('smoke-request-123');
  });
  it('rejects a state-changing request without CSRF', async () => {
    expect((await request(createApp()).post('/sign-out')).status).toBe(403);
  });
  it('renders a branded 404', async () => {
    const res = await request(createApp()).get('/no-such-page');
    expect(res.status).toBe(404);
    expect(res.text).toContain('Page not found');
  });
});
