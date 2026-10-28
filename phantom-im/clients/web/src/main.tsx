import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import { AppProvider } from './app/AppProvider'
import { applyPlatformAttributes } from './platform'
import { installDiagnosticErrorHandlers } from './platform/diagnostics'
import './styles.css'

applyPlatformAttributes()
installDiagnosticErrorHandlers()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AppProvider>
      <App />
    </AppProvider>
  </StrictMode>,
)
