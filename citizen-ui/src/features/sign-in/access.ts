import type { RequestHandler } from 'express';

// This role guard separates the synthetic citizen and staff experiences; it is not real authentication.
export const requireCaseworker: RequestHandler = (req, res, next) => {
  if (!req.session.user) {
    req.session.returnTo = req.method === 'GET' ? req.originalUrl : '/caseworker';
    return res.redirect(303, '/sign-in');
  }
  if (req.session.user.role !== 'caseworker') {
    return res.status(403).render('sign-in/staff-only.njk', { pageTitle: 'Caseworker access required' });
  }
  res.set('Cache-Control', 'no-store');
  next();
};
