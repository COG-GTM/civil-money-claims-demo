import type { RequestHandler } from 'express';

export function normalizeBasePath(value: string): string {
  const path = value.replace(/\/$/, '');
  if (!/^(\/[A-Za-z0-9_-]+)*$/.test(path)) throw new Error('BASE_PATH must contain only slash-separated URL slugs');
  return path;
}

// Keep feature routes origin-relative internally while emitting mount-aware browser URLs.
export function basePathMiddleware(basePath: string): RequestHandler {
  const prefix = (url: string) => url.startsWith('/') && !url.startsWith('//')
    && url !== basePath && !url.startsWith(basePath + '/') ? basePath + url : url;
  return (_req, res, next) => {
    const location = res.location;
    res.location = function (url) { return location.call(this, prefix(url)); };
    const json = res.json;
    res.json = function (body) {
      return json.call(this, body && typeof body.redirect === 'string'
        ? { ...body, redirect: prefix(body.redirect) } : body);
    };
    const send = res.send;
    res.send = function (body) {
      const contentType = res.getHeader('Content-Type');
      if (typeof body === 'string' && (!contentType || String(contentType).includes('text/html'))) {
        body = body.replace(/(\b(?:href|src|action)=["'])(\/[^"']*)/g,
          (_match: string, attribute: string, url: string) => attribute + prefix(url));
      }
      return send.call(this, body);
    };
    next();
  };
}
