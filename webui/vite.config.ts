import { defineConfig } from "vite";

export default defineConfig({
  build: {
    outDir: "../resources/webui",
    emptyOutDir: true,
    sourcemap: true,
  },
});
