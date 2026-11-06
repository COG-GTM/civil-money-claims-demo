import { describe, expect, it } from 'vitest';
import express from 'express';
import request from 'supertest';
import { createApp } from '../app';
import { basePathMiddleware, normalizeBasePath } from './base-path';

describe('unstripped deployment base path', () => {
  it('serves health and HTML beneath the prefix without leaking root links', async () => {
    const app = createApp('/f2ec3d90');
    expect((await request(app).get('/f2ec3d90/health')).body.status).toBe('UP');
    expect((await request(app).get('/health')).status).toBe(404);
    const page = await request(app).get('/f2ec3d90/');
    expect(page.text).toContain('href="/f2ec3d90/assets/application.css"');
    expect(page.text).toContain('src="/f2ec3d90/assets/init.js"');
    expect(page.text).not.toMatch(/(?:href|src|action)="\/(?!f2ec3d90)/);
  });
  it('keeps sign-in, redirects, CSRF and cookies in the mounted service', async () => {
    const browser = request.agent(createApp('/f2ec3d90'));
    const page = await browser.get('/f2ec3d90/sign-in');
    const token = page.text.match(/name="csrf-token" content="([^"]+)"/)![1];
    expect(page.text).toContain('action="/f2ec3d90/sign-in"');
    expect(page.headers['set-cookie'][0]).toContain('Path=/f2ec3d90');
    const signedIn = await browser.post('/f2ec3d90/sign-in').type('form').send({ role: 'claimant', _csrf: token });
    expect(signedIn.status).toBe(303);
    expect(signedIn.headers.location).toBe('/f2ec3d90/dashboard');
    const form = await browser.get('/f2ec3d90/sign-in');
    const csrf = form.text.match(/name="csrf-token" content="([^"]+)"/)![1];
    const signedOut = await browser.post('/f2ec3d90/sign-out').type('form').send({ _csrf: csrf });
    expect(signedOut.headers.location).toBe('/f2ec3d90/sign-in');
    expect(signedOut.headers['set-cookie'][0]).toContain('Path=/f2ec3d90');
  });
  it('prefixes upload redirects but never double-prefixes or changes external URLs', async () => {
    const app = express();
    app.use(basePathMiddleware('/f2ec3d90'));
    app.get('/upload', (_req, res) => res.json({ redirect: '/make-claim/evidence' }));
    app.get('/links', (_req, res) => res.send('<a href="/f2ec3d90/respond">One</a><a href="https://example.test">Two</a><a href="//example.test">Three</a>'));
    expect((await request(app).get('/upload')).body.redirect).toBe('/f2ec3d90/make-claim/evidence');
    const links = (await request(app).get('/links')).text;
    expect(links).not.toContain('/f2ec3d90/f2ec3d90');
    expect(links).toContain('href="https://example.test"');
    expect(links).toContain('href="//example.test"');
  });
  it('normalizes optional trailing slashes and rejects unsafe configuration', () => {
    expect(normalizeBasePath('/f2ec3d90/')).toBe('/f2ec3d90');
    expect(normalizeBasePath('/')).toBe('');
    expect(() => normalizeBasePath('/../service')).toThrow();
    expect(() => normalizeBasePath('/bad path')).toThrow();
  });
});
