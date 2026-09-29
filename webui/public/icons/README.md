# PWA icons

`logo.svg` is the source artwork. The committed PNGs are rendered from it on macOS; they are not generated
at build or deploy time.

Run these commands from this directory:

```sh
icon_tmp="$(mktemp -d)"
qlmanage -t -s 512 -o "$icon_tmp" logo.svg
mv "$icon_tmp/logo.svg.png" icon-512.png
sips -z 192 192 icon-512.png --out icon-192.png
sips -z 180 180 icon-512.png --out apple-touch-icon.png
chmod 0644 icon-512.png icon-192.png apple-touch-icon.png
rmdir "$icon_tmp"
```

The results must remain RGBA PNGs sized 512×512, 192×192 and 180×180 respectively.
