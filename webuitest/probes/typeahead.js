import { h, render } from "preact";
import { useTypeahead } from "../../webui/src/components/Typeahead.tsx";

// Identical option keys ensure resolveActiveKey cannot hide a shared signal behind its fallback.
const KEYS = ["local:1", "local:2", "local:3"];

export const commits = [];
let host;

function Picker(props) {
  const typeahead = useTypeahead({
    keys: KEYS,
    onCommit: (key) => { commits.push(props.name + ":" + key); },
  });
  return h(
    "div",
    null,
    h("input", {
      id: "typeahead-probe-input-" + props.name,
      onKeyDown: typeahead.keyDown,
    }),
    h(
      "p",
      { id: "typeahead-probe-active-" + props.name },
      String(typeahead.activeKey),
    ),
  );
}

export function mount() {
  host = document.createElement("div");
  host.id = "typeahead-probe";
  document.body.appendChild(host);
  render(h("div", null, h(Picker, { name: "one" }), h(Picker, { name: "two" })), host);
  return import.meta.url;
}

export function unmount() {
  render(null, host);
  host.remove();
}
