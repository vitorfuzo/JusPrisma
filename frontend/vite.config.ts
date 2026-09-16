import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    // O backend fica em outra porta no dev. O proxy faz o navegador enxergar tudo na
    // mesma origem, o que importa por causa do cookie de renovação: ele é SameSite=Strict
    // e restrito ao caminho /api/v1/sessoes, e origens distintas o impediriam de viajar.
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
