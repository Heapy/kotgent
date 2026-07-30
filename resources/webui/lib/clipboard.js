/*
 * Clipboard writing shared by the terminal header and the command palette's app-owned action.
 */

export async function writeClipboard(text) {
  if (navigator.clipboard && typeof navigator.clipboard.writeText === "function") {
    try {
      await navigator.clipboard.writeText(text);
      return;
    } catch (_) {
      // A non-secure origin or browser permission can reject the modern API; the click still gives the
      // legacy path the user gesture it needs.
    }
  }

  const textarea = document.createElement("textarea");
  textarea.value = text;
  textarea.readOnly = true;
  textarea.style.position = "fixed";
  textarea.style.opacity = "0";
  document.body.appendChild(textarea);
  try {
    textarea.select();
    if (!document.execCommand("copy")) throw new Error("copy command was rejected");
  } finally {
    textarea.remove();
  }
}
