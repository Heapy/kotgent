// Node runs under one default locale for the whole process, and its argument-less
// `toLocaleLowerCase()` keeps folding as en even under LC_ALL=tr_TR (verified on v24). So the check that
// a rule folds case locale-independently cannot be made by setting a locale — it is made by redirecting
// the default fold to the real Turkish one and asserting the rule never reaches for it.
//
// A tr/az browser folds an uppercase "I" to a dotless "ı" and leaves a typed "i" dotted, so
// `toLocaleLowerCase()` turned "Index the API" into "ındex the apı" and a typed "index" matched nothing
// — for exactly the operators whose locale the code never anticipated. The fix is `toLowerCase()`, and
// this helper is what makes reverting it red instead of green.
//
// This file is deliberately not named `*.test.js`: the node runner's pattern and the file count in
// WebUiLogicTest both key on that suffix, and a helper is not a test.

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
