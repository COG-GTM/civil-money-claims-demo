import 'express-session';
declare module 'express-session' {
  interface SessionData {
    user?: { id: string; role: 'claimant' | 'defendant' | 'caseworker'; name: string };
    csrfToken?: string;
    makeClaim?: Record<string, any>;
    respond?: Record<string, any>;
    returnTo?: string;
    [key: string]: any;
  }
}
declare global {
  namespace Express { interface Request { requestId: string; } }
}
