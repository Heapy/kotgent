import type { RefObject } from "preact";
import { useLayoutEffect } from "preact/hooks";

/** Bound the whole flex stack, including its footer, when a keyboard covers the layout viewport. */
export function useVisiblePane(paneRef: RefObject<HTMLElement>) {
  useLayoutEffect(() => {
    const pane = paneRef.current;
    const app = pane?.closest("#app");
    const viewport = window.visualViewport;
    if (!pane || !app || !viewport) return undefined;
    const clear = () => {
      pane.style.removeProperty("--pane-visible-height");
      app.classList.remove("visual-viewport-shrunken");
    };
    const measure = () => {
      // Safari emits transient zeroes during rotation; retain the last useful geometry.
      if (!Number.isFinite(viewport.height) || !Number.isFinite(viewport.offsetTop) || viewport.height <= 0) return;
      const appHeight = app.getBoundingClientRect().height;
      if (!Number.isFinite(appHeight) || appHeight <= 0) return;
      const style = getComputedStyle(app);
      const safeArea = (Number.parseFloat(style.getPropertyValue("--device-safe-area-top")) || 0) +
        (Number.parseFloat(style.getPropertyValue("--device-safe-area-bottom")) || 0);
      if (viewport.height >= appHeight - safeArea - 1) {
        clear();
        return;
      }
      const bottomMargin = Number.parseFloat(getComputedStyle(pane).marginBottom) || 0;
      const height = Math.floor(viewport.offsetTop + viewport.height - pane.getBoundingClientRect().top - bottomMargin);
      if (!Number.isFinite(height) || height <= 0) return;
      app.classList.add("visual-viewport-shrunken");
      pane.style.setProperty("--pane-visible-height", height + "px");
    };
    measure();
    viewport.addEventListener("resize", measure);
    viewport.addEventListener("scroll", measure);
    window.addEventListener("resize", measure);
    return () => {
      viewport.removeEventListener("resize", measure);
      viewport.removeEventListener("scroll", measure);
      window.removeEventListener("resize", measure);
      clear();
    };
  }, [paneRef]);
}
