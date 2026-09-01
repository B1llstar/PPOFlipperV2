import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import { defineConfig } from 'vite'
import { fileURLToPath, URL } from 'node:url'

// PPOFlipperStar dashboard build config.
//
// `build.outDir` points at ../public (i.e. firebase/public/), which is the directory
// firebase/firebase.json's `hosting.public` already serves - so `npm run build` here followed by
// `firebase deploy --only hosting` (run from firebase/) "just works" with no extra wiring. This
// intentionally OVERWRITES whatever is currently in firebase/public/ (the old static
// GE-Star/flipper-star index.html) - see PPOFlipperStar's dashboard-build task notes for why
// that's the deliberate intent, not an accident: Firebase Hosting only serves one thing at a
// given path, and the old page is being fully replaced by this app's build output.
export default defineConfig({
  plugins: [vue(), tailwindcss()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    outDir: '../public',
    emptyOutDir: true,
    rollupOptions: {
      output: {
        // Chart.js (+ date adapter) and the Firebase SDK are the two heaviest, most independent
        // dependencies here - splitting them into their own chunks keeps the main app bundle
        // small and lets the browser cache these large-but-rarely-changing vendor chunks
        // separately from app code that changes every deploy. Vite 8's Rolldown-based build
        // requires manualChunks as a function (unlike classic Rollup, which also accepted a
        // plain id-list object) - see https://rolldown.rs/reference/OutputOptions.
        manualChunks(id) {
          if (id.includes('node_modules')) {
            if (/chart\.js|vue-chartjs|chartjs-adapter-date-fns|date-fns/.test(id)) return 'charts'
            if (/[\\/]firebase[\\/]|@firebase[\\/]/.test(id)) return 'firebase'
          }
        },
      },
    },
  },
})
