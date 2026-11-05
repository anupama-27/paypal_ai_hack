import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// In dev, /api and /mcp go to the Spring Boot backend so no CORS setup is needed.
const target = process.env.API_TARGET ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': target,
      '/mcp': target,
    },
  },
})
