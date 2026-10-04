import { createContext } from "preact";
import type { ComponentChildren, RefObject } from "preact";
import { useContext, useLayoutEffect, useRef, useState } from "preact/hooks";

interface HeaderPopoverLayout {
  containerRef: RefObject<HTMLElement>;
  headerRef: RefObject<HTMLElement>;
}

export const HeaderPopoverLayoutContext = createContext<HeaderPopoverLayout | null>(null);

interface HeaderPopoverProps {
  id: string;
  label: string;
  trigger: ComponentChildren;
  className?: string;
  panelClass?: string;
  initialFocus?: string;
  children: (close: () => void) => ComponentChildren;
}

/** Nonmodal header controls: light dismiss, Escape, and normal Tab navigation. */
export function HeaderPopover({ id, label, trigger, className = "", panelClass = "", initialFocus, children }: HeaderPopoverProps) {
  const layout = useContext(HeaderPopoverLayoutContext);
  if (!layout) throw new Error("HeaderPopover requires a layout provider");
  const [open, setOpen] = useState(false);
  const button = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLDivElement>(null);
  const close = () => {
    if (panel.current?.contains(document.activeElement)) button.current?.focus({ preventScroll: true });
    setOpen(false);
  };
  const align = () => {
    const pane = layout.containerRef.current;
    const header = layout.headerRef.current;
    if (!pane || !header || !button.current || !panel.current) return;
    const paneBox = pane.getBoundingClientRect();
    const buttonBox = button.current.getBoundingClientRect();
    const left = Math.max(6, Math.min(buttonBox.left - paneBox.left,
      paneBox.width - panel.current.offsetWidth - 6));
    panel.current.style.left = left + "px";
    const top = header.getBoundingClientRect().bottom - paneBox.top;
    panel.current.style.top = top + "px";
    panel.current.style.maxHeight = "calc(100% - " + (top + 8) + "px)";
  };
  // A tab label can change the trigger's position without changing the pane's width.
  useLayoutEffect(() => { if (open) align(); });
  useLayoutEffect(() => {
    if (!open) return undefined;
    const pane = layout.containerRef.current;
    const header = layout.headerRef.current;
    const observer = new ResizeObserver(align);
    if (pane) observer.observe(pane);
    if (header) observer.observe(header);
    if (panel.current) observer.observe(panel.current);
    const outside = (event: Event) => {
      const target = event.target;
      if (target instanceof Node && !panel.current?.contains(target) && !button.current?.contains(target)) close();
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      event.preventDefault();
      event.stopPropagation();
      close();
      button.current?.focus({ preventScroll: true });
    };
    document.addEventListener("pointerdown", outside);
    document.addEventListener("focusin", outside);
    document.addEventListener("keydown", escape, true);
    document.addEventListener("scroll", align, true);
    if (initialFocus) panel.current?.querySelector<HTMLElement>(initialFocus)?.focus();
    return () => {
      observer.disconnect();
      document.removeEventListener("pointerdown", outside);
      document.removeEventListener("focusin", outside);
      document.removeEventListener("keydown", escape, true);
      document.removeEventListener("scroll", align, true);
    };
  }, [open, initialFocus, layout]);

  return <>
    <button ref={button} id={id + "-toggle"} type="button" class={"header-control " + className}
            aria-label={label} title={label} aria-expanded={open} aria-controls={id}
            onClick={() => setOpen(!open)}>{trigger}</button>
    <div ref={panel} id={id} class={"header-popover " + panelClass} hidden={!open}
         role="region" aria-label={label}>
      {children(close)}
    </div>
  </>;
}
