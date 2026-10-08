import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import svgr from 'vite-plugin-svgr'
import { VitePWA } from 'vite-plugin-pwa'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, import.meta.dirname, 'VITE_')
  const apiUrl = env.VITE_API_BASE_URL?.trim()

  return {
    plugins: [
      react(),
      svgr(),

      VitePWA({
        registerType: 'autoUpdate',

        workbox: {
          maximumFileSizeToCacheInBytes: 5 * 1024 * 1024,
        },

        manifest: {
          name: 'KnowONE',
          short_name: 'KnowONE',
          description: 'Knowone PWA',

          start_url: '/',
          display: 'standalone',

          background_color: '#ffffff',
          theme_color: '#ffffff',

          icons: [
            {
              src: '/icon.png',
              sizes: '512x512',
              type: 'image/png',
            },
            {
              src: '/icon.png',
              sizes: '512x512',
              type: 'image/png',
              purpose: 'any maskable',
            },
          ],
        },
      }),
    ],

    server: {
      proxy: apiUrl
        ? {
            '/api': {
              target: new URL(apiUrl).origin,
              changeOrigin: true,
              secure: true,
            },
          }
        : {},
    },
  }
})
