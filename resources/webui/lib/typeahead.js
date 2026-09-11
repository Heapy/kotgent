// Framework-free typeahead rules. Keys remain stable when live lists reorder; autoFirst distinguishes
// lists that should claim Enter immediately from path pickers that should not.

export const NEXT = "next";
export const PREVIOUS = "previous";
export const COMMIT = "commit";
export const DISMISS = "dismiss";
export const COMPOSING = "composing";

/**
 * An explicit choice stamped with its query, so a new query discards it.
 */
export function chooseKey(key, token = null) {
  return { key: key, token: token };
}

/** Which row is active, answered during render and again at event time from the same inputs. */
export function resolveActiveKey(keys, chosen, { autoFirst = true, token = null } = {}) {
  const list = keys || [];
  if (list.length === 0) return null;
  if (chosen && chosen.token === token && list.indexOf(chosen.key) >= 0) return chosen.key;
  return autoFirst ? list[0] : null;
}

/** Arrow movement, wrapping. An active key the list has dropped navigates as if nothing were active. */
export function stepActiveKey(keys, activeKey, delta) {
  const list = keys || [];
  if (list.length === 0) return null;
  const at = list.indexOf(activeKey);
  if (at < 0) return delta > 0 ? list[0] : list[list.length - 1];
  return list[(at + delta + list.length) % list.length];
}

/**
 * Composition wins over every key; older engines identify it only with keyCode 229.
 */
export function typeaheadIntent(event) {
  if (!event) return null;
  if (event.isComposing === true || event.keyCode === 229) return COMPOSING;
  switch (event.key) {
    case "ArrowDown":
      return NEXT;
    case "ArrowUp":
      return PREVIOUS;
    case "Enter":
      return COMMIT;
    case "Escape":
      return DISMISS;
    default:
      return null;
  }
}
