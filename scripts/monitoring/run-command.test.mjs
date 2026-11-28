import assert from 'node:assert/strict';
import { test } from 'node:test';
import { runCommand } from './run-command.mjs';

test('passes stdin without shell interpolation and preserves Long values', async () => {
  const input = '913789102349674503\n한글 $() `literal`\n';
  const output = await runCommand(process.execPath, ['-e', 'process.stdin.pipe(process.stdout)'], { input });
  assert.equal(output, input);
});

test('process completion does not block an event-loop timer', async () => {
  let ticked = false;
  const timer = setTimeout(() => { ticked = true; }, 20);
  try {
    await runCommand(process.execPath, ['-e', 'setTimeout(()=>process.stdout.write("done"),150)']);
    assert.equal(ticked, true);
  } finally { clearTimeout(timer); }
});

test('nonzero exits report code without echoing SQL or stderr contents', async () => {
  await assert.rejects(runCommand(process.execPath, ['-e', 'process.stderr.write("private-fixture");process.exit(3)']), error => {
    assert.equal(error.code, 'COMMAND_FAILED'); assert.equal(error.exitCode, 3);
    assert.equal(error.message.includes('private-fixture'), false); return true;
  });
});

test('a hung process is terminated with a bounded timeout', async () => {
  await assert.rejects(runCommand(process.execPath, ['-e', 'setInterval(()=>{},1000)'], { timeout: 100 }), { code: 'COMMAND_TIMEOUT' });
});
