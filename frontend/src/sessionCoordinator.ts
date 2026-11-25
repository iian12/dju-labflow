type SessionChange = { id: string; kind: 'login' | 'logout' | 'expired' };
const eventKey = 'lf-auth-change-v1';
let revision = 'initial';
let channel: BroadcastChannel | undefined;

function storedChange(): SessionChange | undefined {
  try {
    const value = JSON.parse(localStorage.getItem(eventKey) ?? 'null');
    if (value && typeof value.id === 'string' && ['login', 'logout', 'expired'].includes(value.kind)) return value;
  } catch { /* Storage can be unavailable in a restricted browser context. */ }
}

export function sessionStamp() { return storedChange()?.id ?? revision; }

function receive(value: unknown) {
  if (!value || typeof value !== 'object') return;
  const change = value as SessionChange;
  if (typeof change.id !== 'string' || !['login', 'logout', 'expired'].includes(change.kind)) return;
  const current = storedChange();
  if (change.id === revision || (current && current.id !== change.id)) return;
  revision = change.id;
  window.dispatchEvent(new CustomEvent('labflow-auth-state', { detail: change }));
}

if (typeof window !== 'undefined') {
  revision = sessionStamp();
  if (typeof BroadcastChannel !== 'undefined') {
    try { channel = new BroadcastChannel('labflow-auth'); channel.onmessage = event => receive(event.data); }
    catch { /* Storage events can still notify other tabs. */ }
  }
  window.addEventListener('storage', event => {
    if (event.key === eventKey && event.newValue) {
      try { receive(JSON.parse(event.newValue)); } catch { /* Ignore malformed external values. */ }
    }
  });
}

export function publishSessionChange(kind: SessionChange['kind']) {
  const current = storedChange();
  if (kind === 'expired' && (current?.kind === 'expired' || current?.kind === 'logout')) {
    revision = current.id;
    if (typeof window !== 'undefined') window.dispatchEvent(new Event('labflow-session-expired'));
    return;
  }
  const change: SessionChange = { kind, id: token() };
  revision = change.id;
  try { localStorage.setItem(eventKey, JSON.stringify(change)); } catch { /* Broadcast still works. */ }
  channel?.postMessage(change);
  if (kind === 'expired' && typeof window !== 'undefined') window.dispatchEvent(new Event('labflow-session-expired'));
}

function token() { return typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : `${Date.now()}-${Math.random()}`; }

let lockDatabase: Promise<IDBDatabase> | undefined;
function database() {
  if (!lockDatabase) lockDatabase = new Promise((resolve, reject) => {
    const request = indexedDB.open('labflow-session-lock', 1);
    request.onupgradeneeded = () => request.result.createObjectStore('locks');
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
  return lockDatabase;
}

async function lease(owner: string, action: 'acquire' | 'renew' | 'release'): Promise<boolean> {
  const db = await database();
  return new Promise((resolve, reject) => {
    const transaction = db.transaction('locks', 'readwrite');
    const locks = transaction.objectStore('locks');
    const read = locks.get('session');
    let held = false;
    read.onsuccess = () => {
      const current = read.result as { owner: string; expires: number } | undefined;
      held = current?.owner === owner || (action === 'acquire' && (!current || current.expires < Date.now()));
      if (held) {
        if (action === 'release') locks.delete('session');
        else locks.put({ owner, expires: Date.now() + 60_000 }, 'session');
      }
    };
    transaction.oncomplete = () => resolve(held);
    transaction.onerror = () => reject(transaction.error);
    transaction.onabort = () => reject(transaction.error);
  });
}

export async function withSessionLock<T>(operation: () => Promise<T>): Promise<T> {
  if (typeof window === 'undefined') return operation();
  if (navigator.locks?.request) {
    return navigator.locks.request('labflow-auth-session', { signal: AbortSignal.timeout(45_000) }, operation);
  }
  // IndexedDB read/write transactions provide atomic acquisition when Web Locks is unavailable.
  if (typeof indexedDB === 'undefined') return operation();
  const owner = token();
  const deadline = Date.now() + 45_000;
  let acquired = false;
  try {
    while (!(acquired = await lease(owner, 'acquire'))) {
      if (Date.now() >= deadline) throw new Error('다른 탭의 인증 처리가 끝나지 않았습니다. 잠시 후 다시 시도해 주세요.');
      await new Promise(resolve => setTimeout(resolve, 100));
    }
  } catch (error) {
    if (Date.now() >= deadline) throw error;
    // In storage-disabled contexts, API session probes still recover a rotated cookie.
    return operation();
  }
  const renew = setInterval(() => { void lease(owner, 'renew').catch(() => {}); }, 10_000);
  try { return await operation(); }
  finally { clearInterval(renew); if (acquired) await lease(owner, 'release').catch(() => {}); }
}
