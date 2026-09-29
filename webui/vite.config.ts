import { build, defineConfig } from "vite";
import type { Plugin, ResolvedConfig } from "vite";

function classicServiceWorker(entry: string): Plugin {
  let config: ResolvedConfig;
  return {
    name: "classic-service-worker",
    apply: "build",
    configResolved(resolved) {
      config = resolved;
    },
    buildStart() {
      this.addWatchFile(config.root + "/" + entry);
    },
    // Vite's watch loop does not await closeBundle; keep worker writes inside the completed build.
    async writeBundle() {
      const watchFile = this.addWatchFile.bind(this);
      await build({
        root: config.root,
        configFile: false,
        publicDir: false,
        logLevel: "warn",
        plugins: [{
          name: "classic-service-worker-dependencies",
          buildEnd() {
            for (const id of this.getModuleIds()) watchFile(id);
          },
        }],
        build: {
          outDir: config.build.outDir,
          emptyOutDir: false,
          sourcemap: config.build.sourcemap,
          lib: { entry, formats: ["iife"], name: "kotgentSw", fileName: () => "sw.js" },
        },
      });
    },
  };
}

export default defineConfig({
  plugins: [classicServiceWorker("src/sw.ts")],
  build: {
    outDir: "../resources/webui",
    emptyOutDir: true,
    sourcemap: true,
  },
});
