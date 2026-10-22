Synthetic fixtures only; these contain no provider/user transcript.

`synthetic.jsonl.zst` is `synthetic.jsonl` compressed with the CLI built from Zstandard
v1.5.7 commit f8745da6ff1ad1e7bab384bd1f9d742439278e99:

```sh
zstd -q -f --check synthetic.jsonl -o synthetic.jsonl.zst
```

The same compressed bytes are embedded in ZstdReaderTest to avoid test working-directory assumptions.
Tests additionally construct spec-valid raw/RLE frames for byte boundaries, large expansion and
resource bounds. Neither tests nor production need a system compressor.
