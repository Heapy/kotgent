const icons = {
  sidebar: new URL("../assets/icons/sidebar-simple.svg", import.meta.url).href,
  list: new URL("../assets/icons/list.svg", import.meta.url).href,
  more: new URL("../assets/icons/dots-three.svg", import.meta.url).href,
  caret: new URL("../assets/icons/caret-down.svg", import.meta.url).href,
  columns: new URL("../assets/icons/columns.svg", import.meta.url).href,
  plus: new URL("../assets/icons/plus.svg", import.meta.url).href,
  close: new URL("../assets/icons/x.svg", import.meta.url).href,
  terminal: new URL("../assets/icons/terminal-window.svg", import.meta.url).href,
  task: new URL("../assets/icons/check-square.svg", import.meta.url).href,
  plan: new URL("../assets/icons/file-text.svg", import.meta.url).href,
};

export function Icon({ name }: { name: keyof typeof icons }) {
  return <span class="ui-icon" aria-hidden="true" style={{ maskImage: `url("${icons[name]}")` }} />;
}
