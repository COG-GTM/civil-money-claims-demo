const fs = require('node:fs');
const path = require('node:path');
// Keep each feature's templates and static assets beside its compiled routes.
function copyAssets(source, target) {
  for (const entry of fs.readdirSync(source, { withFileTypes: true })) {
    const from = path.join(source, entry.name);
    const to = path.join(target, entry.name);
    if (entry.isDirectory()) {
      fs.mkdirSync(to, { recursive: true });
      copyAssets(from, to);
    } else if (!entry.name.endsWith('.ts') && !entry.name.endsWith('.scss')) {
      fs.copyFileSync(from, to);
    }
  }
}
copyAssets('src', 'dist');
fs.copyFileSync('node_modules/govuk-frontend/dist/govuk/govuk-frontend.min.js', 'dist/public/govuk-frontend.min.js');
