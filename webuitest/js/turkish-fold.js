// Redirect the locale-sensitive fold to Turkish so tests can prove production code uses the
// locale-independent alternative. Setting a locale cannot do this: Node folds as `en` for the whole
// process even under LC_ALL=tr_TR. This helper is intentionally excluded from the `*.test.js` glob.

export function underTurkishFold(run) {
  const original = String.prototype.toLocaleLowerCase;
  String.prototype.toLocaleLowerCase = function turkish() {
    return original.call(this, "tr");
  };
  try {
    return run();
  } finally {
    String.prototype.toLocaleLowerCase = original;
  }
}
