const icons = {
  folder: new URL("../assets/icons/folder-simple.svg", import.meta.url).href,
  rename: new URL("../assets/icons/pencil-simple.svg", import.meta.url).href,
  command: new URL("../assets/icons/command.svg", import.meta.url).href,
  copy: new URL("../assets/icons/copy.svg", import.meta.url).href,
  lock: new URL("../assets/icons/lock-simple.svg", import.meta.url).href,
  pause: new URL("../assets/icons/pause.svg", import.meta.url).href,
  check: new URL("../assets/icons/check.svg", import.meta.url).href,
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
  up: new URL("../assets/icons/arrow-up.svg", import.meta.url).href,
  down: new URL("../assets/icons/arrow-down.svg", import.meta.url).href,
  left: new URL("../assets/icons/arrow-left.svg", import.meta.url).href,
  right: new URL("../assets/icons/arrow-right.svg", import.meta.url).href,
};

export function Icon({ name }: { name: keyof typeof icons }) {
  return <span class="ui-icon" aria-hidden="true" style={{ maskImage: `url("${icons[name]}")` }} />;
}
