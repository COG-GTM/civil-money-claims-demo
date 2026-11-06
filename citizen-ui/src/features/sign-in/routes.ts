import type { Express } from 'express';

const identities = {
  claimant: { id: 'demo-claimant', role: 'claimant', name: 'Demo claimant', home: '/dashboard' },
  defendant: { id: 'demo-defendant', role: 'defendant', name: 'Demo defendant', home: '/respond' },
  caseworker: { id: 'demo-caseworker', role: 'caseworker', name: 'Demo caseworker', home: '/caseworker' }
} as const;
type Role = keyof typeof identities;

// Only known same-origin feature paths are valid sign-in destinations.
function destination(value: unknown, role: Role): string {
  if (typeof value !== 'string' || /[\\\x00-\x20]/.test(value)) return identities[role].home;
  const prefixes = role === 'caseworker' ? ['caseworker', 'ops'] : role === 'claimant' ? ['dashboard', 'make-claim'] : ['respond'];
  return prefixes.some(prefix => new RegExp(`^/${prefix}(?:[/?]|$)`).test(value))
    && !value.split(/[?#]/)[0].split('/').some(segment => /^(\.|%2e){1,2}$/i.test(segment))
    ? value : identities[role].home;
}

export function registerRoutes(app: Express): void {
  app.get('/sign-in', (req, res) => {
    res.set('Cache-Control', 'no-store');
    if (typeof req.query.returnTo === 'string') req.session.returnTo = req.query.returnTo;
    res.render('sign-in/index.njk', { pageTitle: 'Sign in to the demo' });
  });

  app.post('/sign-in', async (req, res) => {
    const role = req.body?.role;
    if (typeof role !== 'string' || !Object.hasOwn(identities, role)) {
      return res.status(400).render('sign-in/index.njk', {
        pageTitle: 'Error: Sign in to the demo', roleError: 'Select how you want to use the demo',
        errors: [{ text: 'Select how you want to use the demo', href: '#role' }]
      });
    }
    const identity = identities[role as Role];
    const returnTo = destination(req.session.returnTo, role as Role);
    // Rotate the cookie and clear the previous role's form state when changing identity.
    await new Promise<void>((resolve, reject) => req.session.regenerate(error => error ? reject(error) : resolve()));
    req.session.user = { id: identity.id, role: identity.role, name: identity.name };
    await new Promise<void>((resolve, reject) => req.session.save(error => error ? reject(error) : resolve()));
    res.redirect(303, returnTo);
  });

  app.post('/sign-out', async (req, res) => {
    await new Promise<void>((resolve, reject) => req.session.destroy(error => error ? reject(error) : resolve()));
    res.clearCookie('cmc.sid', { path: '/' });
    res.redirect(303, '/sign-in');
  });
}
