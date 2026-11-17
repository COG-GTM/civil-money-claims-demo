import { describe, it, expect } from 'vitest';
import { createApp } from '../app';
import { warmUp } from './warm-up';

describe('startup warm-up', () => {
  it('renders the hub once through the full middleware stack', async () => {
    expect(await warmUp(createApp())).toBe(200);
  });
});
