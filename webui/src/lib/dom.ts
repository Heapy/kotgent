/** Callback refs unregister only the node they mounted, including keyed nodes moved between parents. */
export function registerElement<K, E extends HTMLElement>(elements: Map<K, E>, key: K, element: E | null) {
  if (!element) return;
  elements.set(key, element);
  return () => {
    if (elements.get(key) === element) elements.delete(key);
  };
}

/** Keyboard shortcuts must leave native editing controls and modal dialogs alone. */
export function isEditingTarget(target: EventTarget | null): boolean {
  return target instanceof Element &&
    (target.closest("input,textarea,select,dialog") !== null ||
      (target instanceof HTMLElement && target.isContentEditable));
}
