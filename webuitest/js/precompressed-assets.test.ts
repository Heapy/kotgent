import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { brotliDecompressSync, gunzipSync } from "node:zlib";

const repoRoot = fileURLToPath(new URL("../../", import.meta.url));

function assertPrecompressedAssets(root: string): string[] {
  const files = readdirSync(root, { recursive: true, encoding: "utf8" });
  const assets = files.filter((file) => /^assets\/.*\.(js|css)$/.test(file));
  assert.ok(assets.some((file) => file.endsWith(".js")), "the build emits JavaScript");
  assert.ok(assets.some((file) => file.endsWith(".css")), "the build emits CSS");
  for (const file of assets) {
    assert.ok(files.includes(file + ".br"), `${file} has a Brotli sibling`);
    assert.ok(files.includes(file + ".gz"), `${file} has a gzip sibling`);
  }
  for (const file of files.filter((file) => /\.(br|gz)$/.test(file))) {
    const original = file.slice(0, -3);
    assert.ok(assets.includes(original), `${file} compresses only an emitted assets JS/CSS file`);
    const decompress = file.endsWith(".br") ? brotliDecompressSync : gunzipSync;
    assert.deepEqual(decompress(readFileSync(join(root, file))), readFileSync(join(root, original)), file);
  }
  return assets.sort();
}

test("built assets have matching Brotli and gzip siblings", () => {
  assertPrecompressedAssets(join(repoRoot, "resources/webui"));
});

interface WatchEvent {
  code: string;
  error?: unknown;
}

interface Watcher {
  on(name: "event", listener: (event: WatchEvent) => void): void;
  off(name: "event", listener: (event: WatchEvent) => void): void;
  close(): Promise<void>;
}

type WatchBuild = (config: object) => Promise<Watcher>;

function nextBuild(watcher: Watcher): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => finish(new Error("Vite watch build timed out")), 20_000);
    const onEvent = (event: WatchEvent) => {
      if (event.code === "ERROR") finish(event.error);
      if (event.code === "BUNDLE_END") finish();
    };
    function finish(error?: unknown) {
      clearTimeout(timer);
      watcher.off("event", onEvent);
      if (error) reject(error);
      else resolve();
    }
    watcher.on("event", onEvent);
  });
}

function assertUncompressedAssets(root: string): string[] {
  const files = readdirSync(root, { recursive: true, encoding: "utf8" });
  const assets = files.filter((file) => /^assets\/.*\.(js|css)$/.test(file));
  assert.ok(assets.some((file) => file.endsWith(".js")), "the watch build emits JavaScript");
  assert.ok(assets.some((file) => file.endsWith(".css")), "the watch build emits CSS");
  assert.deepEqual(files.filter((file) => /\.(br|gz)$/.test(file)), [], "watch builds emit no compressed siblings");
  return assets.sort();
}

test("watch builds emit no compressed siblings on any rebuild", { timeout: 60_000 }, async () => {
  const require = createRequire(new URL("../../webui/package.json", import.meta.url));
  const { build } = await import(require.resolve("vite")) as { build: WatchBuild };
  const buildDir = join(repoRoot, "build");
  mkdirSync(buildDir, { recursive: true });
  const root = mkdtempSync(join(buildDir, "webui-precompression-"));
  const outDir = join(root, "dist");
  let watcher: Watcher | undefined;
  try {
    mkdirSync(join(root, "src"));
    writeFileSync(join(root, "index.html"), '<script type="module" src="/src/main.js"></script>');
    writeFileSync(join(root, "src/sw.ts"), 'self.addEventListener("fetch", () => {});');
    writeFileSync(join(root, "src/main.css"), "body { color: red; }");
    const entry = join(root, "src/main.js");
    writeFileSync(entry, 'import "./main.css"; document.title = "first";');
    watcher = await build({
      configFile: join(repoRoot, "webui/vite.config.ts"),
      root,
      logLevel: "silent",
      build: { outDir, watch: {} },
    });
    await nextBuild(watcher);
    const firstAssets = assertUncompressedAssets(outDir);
    const rebuilt = nextBuild(watcher);
    writeFileSync(entry, 'import "./main.css"; document.title = "rebuilt";');
    await rebuilt;
    assert.notDeepEqual(assertUncompressedAssets(outDir), firstAssets, "the rebuild emits changed assets");
  } finally {
    await watcher?.close();
    rmSync(root, { recursive: true, force: true });
  }
});
