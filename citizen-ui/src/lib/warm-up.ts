import { createServer } from 'node:http';
import type { AddressInfo } from 'node:net';
import type { Express } from 'express';

// The first render compiles Nunjucks templates and loads lazy modules; do it before taking traffic.
export async function warmUp(app: Express, path = '/'): Promise<number> {
  const server = createServer(app);
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    const { port } = server.address() as AddressInfo;
    const res = await fetch(`http://127.0.0.1:${port}${path}`, {
      headers: { 'user-agent': 'startup-warmup', 'x-request-id': 'startup-warmup' } });
    await res.arrayBuffer();
    return res.status;
  } finally {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
}
