import type { JSX } from "preact";
import { Icon } from "./Icon.tsx";

export function SidebarToggle({ collapsed, onToggle }: {
  collapsed: boolean;
  onToggle: JSX.MouseEventHandler<HTMLButtonElement>;
}) {
  return <button id="sidebar-toggle" class="icon-button icon-button-small sidebar-toggle" type="button"
    aria-label={collapsed ? "Expand sidebar" : "Collapse sidebar"}
    aria-expanded={!collapsed} aria-controls="sidebar"
    title={collapsed ? "Expand sidebar (⌘.)" : "Collapse sidebar (⌘.)"}
    onClick={onToggle}><Icon name="sidebar" /></button>;
}
