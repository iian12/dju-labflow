import { createRoot } from 'react-dom/client';
import StudioApp from './StudioApp.tsx';
import './style.css';
import './theme.css';

const root = document.getElementById('root');
if (!root) throw new Error('Root element was not found');
createRoot(root).render(<StudioApp/>);
