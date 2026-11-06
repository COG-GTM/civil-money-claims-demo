// Send the CSRF header before the server reads the multipart body.
(() => {
const form = document.getElementById('evidence-upload');
if (form) {
  const uploadButton = document.getElementById('upload-button');
  uploadButton.disabled = false;
  uploadButton.removeAttribute('aria-disabled');
  uploadButton.classList.remove('govuk-button--disabled');
  const summary = document.querySelector('.govuk-error-summary');
  if (summary) { summary.setAttribute('tabindex', '-1'); summary.focus(); }
  form.addEventListener('submit', async event => {
    event.preventDefault();
    const button = document.getElementById('upload-button');
    const status = document.getElementById('upload-status');
    button.disabled = true;
    status.textContent = 'Uploading your file. Please wait.';
    try {
      const response = await fetch(form.action, { method: 'POST', body: new FormData(form), credentials: 'same-origin',
        headers: { 'X-CSRF-Token': document.querySelector('meta[name="csrf-token"]').content } });
      if (response.ok && response.headers.get('content-type')?.includes('application/json')) {
        const result = await response.json();
        window.location.assign(result.redirect);
        return;
      }
      // Render the same-origin server error page, including its GOV.UK error summary/request ID.
      const html = await response.text();
      document.open();
      document.write(html);
      document.close();
    } catch {
      status.textContent = 'We could not confirm the upload. Refresh this page to check the files added before trying again.';
      button.disabled = false;
    }
  });
}
})();
