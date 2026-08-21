// The selection rules of the typeahead-listbox primitive, as pure functions of the current option keys
// and the operator's last explicit choice. components/Typeahead.js binds them to Preact; nothing here
// imports a framework, a signal or the DOM, so webuitest/js/typeahead.test.js proves every rule at the
// node tier instead of through a browser.
//
// One representation: keys. Three of the four call sites carried an index with a `-1` sentinel and the
// fourth carried a task ref. An index names a position, and positions move underneath a list that a
// live update or a keystroke can rewrite between two events. A key names the row, so a row that left
// the list resolves to `null` — the sentinel becomes a value the rules return rather than a number four
// callers agreed to read as "nothing".
//
// `autoFirst` is the one real difference between the callers and stays a parameter: the palette and the
// link picker open with their first row active, while a path picker opens with none, so an Enter the
// operator never aimed at the list still reaches the form behind it.

export const NEXT = "next";
export const PREVIOUS = "previous";
export const COMMIT = "commit";
export const DISMISS = "dismiss";
export const COMPOSING = "composing";

/**
 * An explicit choice, stamped with the query it was made under. The stamp is what makes "a new query
 * starts over" a rule rather than an effect: without it the picker either forgot a choice the operator
 * had just made, or kept one made against a list that no longer exists.
 */
export function chooseKey(key, token = null) {
  return { key: key, token: token === undefined ? null : token };
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
 * What a keydown means to a listbox. Composition is answered first and for every key: an IME candidate
 * commit arrives as `key === "Enter"` with `isComposing` set, and older engines only set `keyCode` to
 * 229, so both are read. The link picker's Enter is the only one in this app that commits a mutating
 * POST — treating a candidate commit as a selection linked a task the operator never chose.
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
