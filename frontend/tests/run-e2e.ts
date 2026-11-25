import { spawn } from 'node:child_process';
import { readdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { createServer } from 'vite';

const root = fileURLToPath(new URL('../', import.meta.url));
const files = (await readdir(new URL('./', import.meta.url)))
  // Former sample-data layout tests stay as design references; production now uses real API data.
  .filter(file => ['api.test.ts', 'api-workbench.e2e.ts', 'auth.e2e.ts', 'export.e2e.ts', 'heartbeat.e2e.ts', 'records.e2e.ts', 'session.e2e.ts', 'pagination.e2e.ts', 'studio.live.e2e.ts'].includes(file))
  .sort()
  .map(file => fileURLToPath(new URL(file, import.meta.url)));
if (!files.length) throw new Error('No E2E tests found');

// An isolated ephemeral port keeps tests independent of the user's dev server.
const server = await createServer({
  root,
  server: { host: '127.0.0.1', port: 0, open: false },
});
try {
  await server.listen();
  const address = server.httpServer?.address();
  if (!address || typeof address === 'string') throw new Error('Test server did not start');
  const child = spawn(process.execPath, ['--test', '--test-concurrency=1', ...files], {
    cwd: root,
    stdio: 'inherit',
    env: { ...process.env, LABFLOW_BASE_URL: `http://127.0.0.1:${address.port}` },
  });
  process.exitCode = await new Promise<number>((resolve, reject) => {
    child.once('error', reject);
    child.once('exit', code => resolve(code ?? 1));
  });
} finally {
  await server.close();
}
