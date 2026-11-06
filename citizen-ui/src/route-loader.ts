import { existsSync, readdirSync } from 'node:fs';
import path from 'node:path';
import type { Express } from 'express';
import { createJiti } from 'jiti';

export const featuresRoot = path.join(__dirname, 'features');
export function featureDirectories(root = featuresRoot): string[] {
  if (!existsSync(root)) return [];
  return readdirSync(root, { withFileTypes: true })
    .filter(entry => entry.isDirectory()).map(entry => entry.name).sort();
}

// New features are discovered without editing this shared registry.
export function registerFeatureRoutes(app: Express, root = featuresRoot): void {
  const loadTypescript = createJiti(__filename, { fsCache: false });
  for (const name of featureDirectories(root)) {
    const base = path.join(root, name, 'routes');
    const entry = existsSync(`${base}.js`) ? `${base}.js` : `${base}.ts`;
    if (!existsSync(entry)) continue;
    const feature = (entry.endsWith('.js') ? require(entry) : loadTypescript(entry)) as { registerRoutes: (app: Express) => void };
    if (typeof feature.registerRoutes !== 'function') throw new Error(`${name} must export registerRoutes`);
    feature.registerRoutes(app);
  }
}
