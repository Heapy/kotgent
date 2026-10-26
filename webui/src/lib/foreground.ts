/** One return from background may deliver both focus and visibilitychange. Refresh only once. */
export function watchForeground(refresh: () => void) {
  let away = document.visibilityState === "hidden";
  const markAway = () => { away = true; };
  const returned = () => {
    if (!away || document.visibilityState !== "visible") return;
    away = false;
    refresh();
  };
  const visibility = () => {
    if (document.visibilityState === "hidden") markAway();
    else returned();
  };
  window.addEventListener("blur", markAway);
  window.addEventListener("focus", returned);
  document.addEventListener("visibilitychange", visibility);
  return () => {
    window.removeEventListener("blur", markAway);
    window.removeEventListener("focus", returned);
    document.removeEventListener("visibilitychange", visibility);
  };
}
