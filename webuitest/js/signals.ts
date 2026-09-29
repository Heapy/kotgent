import type * as SignalsCore from "../../webui/node_modules/@preact/signals-core/dist/signals-core.d.ts";

// Node resolves bare specifiers from the importing file, and no node_modules sits above webuitest/js. Load the
// ESM file webui's state modules resolve so that tests observe their reactive graph rather than a second copy.
const signalsCore: typeof SignalsCore = await import(
  new URL("../../webui/node_modules/@preact/signals-core/dist/signals-core.mjs", import.meta.url).href
);

export const effect = signalsCore.effect;
