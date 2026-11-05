import { defineConfig } from "vite";

export default defineConfig({
  clearScreen: false,
  server: {
    host: "127.0.0.1",
    port: 1420,
    strictPort: true
  },
  envPrefix: ["VITE_", "TAURI_"],
  build: {
    // Android System WebView на телефонах бывает старым (Chrome 80-х версий), поэтому для
    // Android синтаксис понижаем; desktop-сборки собираются как раньше.
    // TAURI_ENV_PLATFORM повторяет target triple: "android" или "androideabi" (armv7).
    target: process.env.TAURI_ENV_PLATFORM?.startsWith("android") ? "chrome80" : "es2022",
    minify: false
  }
});
