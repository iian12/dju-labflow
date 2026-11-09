import { useSyncExternalStore } from 'react'
import ApiWorkbench from './ApiWorkbench'
import ProductApp from './ProductApp'

function subscribe(callback: () => void) {
  window.addEventListener('hashchange', callback)
  return () => window.removeEventListener('hashchange', callback)
}

export default function App() {
  const hash = useSyncExternalStore(subscribe, () => window.location.hash)
  if (hash.startsWith('#/api-test')) return <ApiWorkbench selectedId={hash.split('/')[2]} />
  return <ProductApp hash={hash} />
}