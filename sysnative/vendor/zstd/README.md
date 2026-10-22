# Bundled Zstandard decoder

Upstream: https://github.com/facebook/zstd, tag `v1.5.7`, peeled commit
`f8745da6ff1ad1e7bab384bd1f9d742439278e99` (tag object
`ac66b19e6bd6b83238bf008eecc1298105298532`). We select the BSD license in `LICENSE`.
The amalgamation retains the upstream copyright and license notices.

`zstddeclib.c` is the unmodified official decompression-only amalgamation. SHA-256:
`5eb38abe0a7eea13674b312a006df72002d4192f0f0b4d09e6f03fb927a1f3d0`.
The GitHub commit tarball used for generation has SHA-256
`4b0bd1f0cfb25e61b9103c35f27395530ff5b4c0d2513a00fd745849e85ea52c`.

To reproduce, check out the exact commit, then run from its `build/single_file_libs`:

```sh
python3 combine.py -r ../../lib -x legacy/zstd_legacy.h -o zstddeclib.c zstddeclib-in.c
shasum -a 256 zstddeclib.c
```

See the [upstream integration guide](https://github.com/facebook/zstd/blob/v1.5.7/build/single_file_libs/README.md).
`sysnative/cinterop/include` links this directory into Toolchain's automatic header search path.
The body of `zstd.def` includes the source directly, hides upstream symbols and exports only a narrow
static wrapper to Kotlin. The generated cinterop bitcode carries the implementation into each Native
binary. No installed `zstd`, Homebrew library, runtime download or temporary decompressed file is used.
Legacy pre-1.0 frames, dictionaries and oversized windows are not supported.

On 2026-10-22 the [upstream advisory list](https://github.com/facebook/zstd/security/advisories)
reported no published security advisories. This is the checked source, not a guarantee of no defects.
Recheck upstream advisories when updating this pin.

The decoder accepts at most a 128 MiB window. Input/output steps are 32 KiB; retained decoded windows
are at most 256 KiB. Synchronous prefixes have an 8 MiB input and 4,096-step budget; compressed tails
yield between steps and validate complete frames, including checksums and concatenated frames.
Prefixes intentionally do not validate unread suffixes. The constants and lifecycle live in
`sysnative/src/host/ZstdReader.kt`; all file opens validate the opened regular descriptor with
read-only, CLOEXEC and nonblocking flags.
