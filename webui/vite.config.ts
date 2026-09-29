import { randomUUID } from "node:crypto";
import { readFile, rename, rm, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { brotliCompressSync, constants, gzipSync } from "node:zlib";
import { build, defineConfig } from "vite";
import type { Plugin, ResolvedConfig } from "vite";

async function writeAtomically(path: string, bytes: Uint8Array): Promise<void> {
  const temporary = `${path}.${randomUUID()}.tmp`;
  try {
    await writeFile(temporary, bytes);
    await rename(temporary, path);
  } finally {
    await rm(temporary, { force: true });
  }
}

function precompressedAssets(): Plugin {
  let config: ResolvedConfig;
  return {
    name: "precompressed-assets",
    apply: "build",
    configResolved(resolved) {
      config = resolved;
    },
    async writeBundle(options, bundle) {
      if (this.meta.watchMode) return;
      const outDir = resolve(config.root, options.dir ?? config.build.outDir);
      for (const fileName of Object.keys(bundle)) {
        if (!/^assets\/.*\.(js|css)$/.test(fileName)) continue;
        const path = resolve(outDir, fileName);
        const bytes = await readFile(path);
        await writeAtomically(path + ".br", brotliCompressSync(bytes, {
          params: {
            [constants.BROTLI_PARAM_QUALITY]: 11,
            [constants.BROTLI_PARAM_MODE]: constants.BROTLI_MODE_TEXT,
          },
        }));
        await writeAtomically(path + ".gz", gzipSync(bytes, { level: 9 }));
      }
    },
  };
}

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
  plugins: [classicServiceWorker("src/sw.ts"), precompressedAssets()],
  build: {
    outDir: "../resources/webui",
    emptyOutDir: true,
    sourcemap: true,
  },
});
