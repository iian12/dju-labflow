// Record transport failures without request credentials, bodies, query strings or raw error text.
function safeName(value, fallback) {
  return typeof value === 'string' && /^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(value) ? value : fallback;
}

function causeDetails(error, depth = 0, seen = new Set()) {
  if (!error || typeof error !== 'object' || depth > 4 || seen.has(error)) return undefined;
  seen.add(error);
  const result = { name: safeName(error.name, 'Error') };
  if (typeof error.code === 'string' && /^[A-Z0-9_]{1,64}$/.test(error.code)) result.code = error.code;
  if (Number.isInteger(error.errno)) result.errno = error.errno;
  if (typeof error.syscall === 'string' && /^[a-z_]{1,32}$/.test(error.syscall)) result.syscall = error.syscall;
  const cause = causeDetails(error.cause, depth + 1, seen);
  if (cause) result.cause = cause;
  if (Array.isArray(error.errors)) result.errors = error.errors.slice(0, 5).map(item => causeDetails(item, depth + 1, seen)).filter(Boolean);
  return result;
}

function firstCode(error) {
  if (!error) return undefined;
  return error?.code ?? firstCode(error?.cause) ?? error?.errors?.map(firstCode).find(Boolean);
}

export async function observeHttp({ url, options = {}, operation, phase, consume, onFailure, fetchImpl = globalThis.fetch }) {
  const target = new URL(url), started = Date.now();
  let stage = 'request', status;
  try {
    const response = await fetchImpl(url, options);
    status = response.status;
    stage = 'response-body';
    return await consume(response);
  } catch (error) {
    const details = {
      time: new Date().toISOString(), phase, operation,
      method: (options.method ?? 'GET').toUpperCase(),
      origin: target.origin, path: target.pathname,
      stage, milliseconds: Date.now() - started,
      ...(status === undefined ? {} : { status }),
      error: causeDetails(error),
    };
    const code = firstCode(details.error) ?? details.error?.name ?? 'UNKNOWN';
    // One attempt only: refresh/logout and writes must never be silently retried after a lost reply.
    try { await onFailure?.(details); }
    catch { details.diagnosticWriteFailed = true; }
    const failure = new Error(`${details.method} ${details.origin}${details.path} failed (${phase}/${operation}/${stage}; ${code})`);
    failure.name = 'HttpOperationError';
    failure.details = details;
    throw failure;
  }
}
