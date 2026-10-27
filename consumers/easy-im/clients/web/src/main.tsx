import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import { applyPlatformAttributes } from './platform'
import './styles.css'

applyPlatformAttributes()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
