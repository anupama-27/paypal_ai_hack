import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { AllCommunityModule, ModuleRegistry } from 'ag-grid-community'
import App from './App'
import './styles.css'

ModuleRegistry.registerModules([AllCommunityModule])

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
)
