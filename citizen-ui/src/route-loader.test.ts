import { afterEach, describe, expect, it } from 'vitest';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import express from 'express';
import request from 'supertest';
import { featureDirectories, registerFeatureRoutes } from './route-loader';

const roots: string[] = [];
afterEach(() => roots.splice(0).forEach(root => rmSync(root, { recursive: true, force: true })));
describe('feature auto-loader', () => {
  it('loads TypeScript routes and sibling imports without a shared registry', async () => {
    const root = mkdtempSync(path.join(tmpdir(), 'cmc-routes-'));
    roots.push(root);
    mkdirSync(path.join(root, 'example'));
    writeFileSync(path.join(root, 'example/helper.ts'), 'export const message: string = "loaded";');
    writeFileSync(path.join(root, 'example/routes.ts'), 'import { message } from "./helper"; export function registerRoutes(app: any): void { app.get("/fixture", (_req: any, res: any) => res.send(message)); }');
    const app = express();
    registerFeatureRoutes(app, root);
    expect(featureDirectories(root)).toEqual(['example']);
    expect((await request(app).get('/fixture')).text).toBe('loaded');
  });
  it('ignores empty feature folders until routes exist', () => {
    const root = mkdtempSync(path.join(tmpdir(), 'cmc-routes-'));
    roots.push(root);
    mkdirSync(path.join(root, 'pending'));
    expect(() => registerFeatureRoutes(express(), root)).not.toThrow();
  });
});
