import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import svgr from 'vite-plugin-svgr'
import { VitePWA } from 'vite-plugin-pwa'

export default defineConfig({
  plugins: [
    react(),
    svgr(),

    VitePWA({
      registerType: 'autoUpdate',

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
    proxy: {
      '/api': {
        target: 'https://api.godlife.likelion.uk',
        changeOrigin: true,
        secure: true,
      },
    },
  },
})
