// Connection options describe one transport hop, not the upstream application's end-to-end response.
export function withoutHopByHop(headers) {
  const blocked = new Set([
    'connection', 'keep-alive', 'proxy-connection', 'proxy-authenticate',
    'proxy-authorization', 'te', 'trailer', 'transfer-encoding', 'upgrade',
  ]);
  for (const [name, value] of Object.entries(headers)) {
    if (name.toLowerCase() !== 'connection') continue;
    for (const option of (Array.isArray(value) ? value.join(',') : String(value ?? '')).split(',')) {
      if (option.trim()) blocked.add(option.trim().toLowerCase());
    }
  }
  return Object.fromEntries(Object.entries(headers).filter(([name]) => !blocked.has(name.toLowerCase())));
}
