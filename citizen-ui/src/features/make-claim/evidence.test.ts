import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { describe, expect, it, vi } from 'vitest';

const script = readFileSync(new URL('./assets/evidence.js', import.meta.url), 'utf8');

function browser(fetch: ReturnType<typeof vi.fn>) {
  let submit!: (event: { preventDefault(): void }) => Promise<void>;
  const form = { action: '/make-claim/evidence/upload', addEventListener: (_name: string, handler: typeof submit) => { submit = handler; } };
  const button = { disabled: true, removeAttribute: vi.fn(), classList: { remove: vi.fn() } };
  const status = { textContent: '' };
  const assign = vi.fn();
  const document = {
    getElementById: (id: string) => ({ 'evidence-upload': form, 'upload-button': button, 'upload-status': status })[id],
    querySelector: (selector: string) => selector.startsWith('meta') ? { content: 'csrf-token' } : null,
    open: vi.fn(), write: vi.fn(), close: vi.fn()
  };
  const context = { document, window: { location: { assign } }, fetch, FormData: class {} };
  runInNewContext(script, context);
  return { submit: () => submit({ preventDefault: vi.fn() }), document, button, status, assign, context };
}

describe('evidence browser script', () => {
  it('enables uploads only with JS and sends the CSRF header before redirecting', async () => {
    const fetch = vi.fn().mockResolvedValue({ ok: true, headers: new Headers({ 'content-type': 'application/json' }), json: async () => ({ redirect: '/make-claim/evidence?uploaded=true' }) });
    const page = browser(fetch);
    expect(page.button.disabled).toBe(false);
    expect(page.button.removeAttribute).toHaveBeenCalledWith('aria-disabled');
    expect(page.button.classList.remove).toHaveBeenCalledWith('govuk-button--disabled');
    await page.submit();
    expect(fetch).toHaveBeenCalledWith('/make-claim/evidence/upload', expect.objectContaining({ method: 'POST', headers: { 'X-CSRF-Token': 'csrf-token' } }));
    expect(page.assign).toHaveBeenCalledWith('/make-claim/evidence?uploaded=true');
  });
  it('displays the server error page and can initialize again after document replacement', async () => {
    const page = browser(vi.fn().mockResolvedValue({ ok: false, text: async () => '<html>Upload error</html>' }));
    await page.submit();
    expect(page.document.write).toHaveBeenCalledWith('<html>Upload error</html>');
    expect(() => runInNewContext(script, page.context)).not.toThrow();
  });
  it('re-enables the button and explains how to recover after a network failure', async () => {
    const page = browser(vi.fn().mockRejectedValue(new Error('Network error')));
    await page.submit();
    expect(page.button.disabled).toBe(false);
    expect(page.status.textContent).toContain('Refresh this page to check the files added');
    expect(page.assign).not.toHaveBeenCalled();
  });
});
