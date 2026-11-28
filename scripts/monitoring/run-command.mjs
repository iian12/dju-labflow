import { spawn } from 'node:child_process';

// Keep socket-close events, HTTP timeouts and monitoring responsive during SSH/SQL checks.
export function runCommand(file, args, { input = '', timeout = 30000 } = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(file, args, { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
    let output = '', stderrBytes = 0, settled = false;
    const timer = setTimeout(() => {
      child.kill();
      finish(Object.assign(new Error('Subprocess timed out'), { code: 'COMMAND_TIMEOUT' }));
    }, timeout);
    function finish(error) {
      if (settled) return;
      settled = true; clearTimeout(timer);
      if (error) reject(error); else resolve(output);
    }
    child.once('error', finish);
    child.stdout.setEncoding('utf8');
    child.stdout.on('data', chunk => { output += chunk; });
    child.stderr.on('data', chunk => { stderrBytes += chunk.length; });
    child.once('close', code => finish(code === 0 ? undefined : Object.assign(
      new Error(`Subprocess exited ${code}; stderr bytes=${stderrBytes}`), { code: 'COMMAND_FAILED', exitCode: code },
    )));
    child.stdin.on('error', error => { if (error.code !== 'EPIPE') finish(error); });
    child.stdin.end(input);
  });
}
