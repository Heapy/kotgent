import { useEffect, useState } from "preact/hooks";
import { readDiffImage, type DiffPatch } from "../lib/diff.ts";

type Side = "before" | "after";
const title = { before: "Before", after: "After" };

export function DiffImages({ sessionId, patch }: { sessionId: string; patch: DiffPatch }) {
  const images = patch.images!;
  const [sources, setSources] = useState<Partial<Record<Side, string>>>({});
  const [errors, setErrors] = useState<Partial<Record<Side, string>>>({});
  useEffect(() => {
    const controller = new AbortController();
    const urls: string[] = [];
    setSources({}); setErrors({});
    for (const side of ["before", "after"] as const) {
      if (!images[side]?.image) continue;
      void readDiffImage(sessionId, patch, side, controller.signal).then(blob => {
        if (controller.signal.aborted) return;
        const url = URL.createObjectURL(blob);
        urls.push(url);
        setSources(previous => ({ ...previous, [side]: url }));
      }).catch(error => {
        if (!controller.signal.aborted) setErrors(previous => ({ ...previous, [side]: error instanceof Error ? error.message : "Cannot load this image." }));
      });
    }
    return () => { controller.abort(); urls.forEach(url => URL.revokeObjectURL(url)); };
  }, [sessionId, patch]);
  const [view, setView] = useState<"side" | "overlay">("side");
  const [opacity, setOpacity] = useState(50);
  const [failed, setFailed] = useState({ before: false, after: false });
  const [sizes, setSizes] = useState({
    before: { width: images.before?.image?.width ?? 0, height: images.before?.image?.height ?? 0 },
    after: { width: images.after?.image?.width ?? 0, height: images.after?.image?.height ?? 0 },
  });
  const canOverlay = !!sources.before && !!sources.after && !failed.before && !failed.after;
  const overlay = view === "overlay" && canOverlay;
  const width = Math.max(sizes.before.width, sizes.after.width), height = Math.max(sizes.before.height, sizes.after.height);
  function picture(side: Side, layered = false) {
    const value = images[side];
    if (!value) return <p>{side === "before" ? "File did not exist" : "File deleted"}</p>;
    if (value.problem) return <p role="status">{value.problem.message}</p>;
    if (errors[side]) return <p role="status">{errors[side]}</p>;
    if (value.image && !sources[side]) return <p role="status">Loading image…</p>;
    if (!value.image || failed[side]) return <p role="status">Cannot decode this image.</p>;
    return <img src={sources[side]} alt={`${title[side]} image`} draggable={false}
      style={{ width: `${sizes[side].width / width * 100}%`, opacity: layered && side === "after" ? opacity / 100 : 1 }}
      onLoad={e => {
        const { naturalWidth, naturalHeight } = e.currentTarget;
        setSizes(previous => previous[side].width === naturalWidth && previous[side].height === naturalHeight ? previous
          : { ...previous, [side]: { width: naturalWidth, height: naturalHeight } });
      }} onError={() => setFailed(previous => ({ ...previous, [side]: true }))}/>;
  }
  function caption(side: Side) {
    const image = images[side]?.image;
    return <><strong>{title[side]}</strong>{image && <span>{sizes[side].width} × {sizes[side].height} px · {(image.byteLength / 1024).toFixed(1)} KiB</span>}</>;
  }
  return <section class="diff-images" aria-label="Image comparison">
    <div class="diff-image-tools">
      <div class="diff-segments" role="group" aria-label="Image view">
        <button type="button" aria-pressed={!overlay} onClick={() => setView("side")}>Side by side</button>
        <button type="button" aria-pressed={overlay} disabled={!canOverlay} onClick={() => setView("overlay")}>Overlay</button>
      </div>
      {overlay && <label>After opacity<input type="range" min="0" max="100" value={opacity} onInput={e => setOpacity(Number(e.currentTarget.value))}/><output>{opacity}%</output></label>}
    </div>
    {overlay ? <>
      <div class="diff-image-captions"><div>{caption("before")}</div><div>{caption("after")}</div></div>
      <div class="diff-image-canvas diff-image-overlay" style={{ width: `${width}px`, aspectRatio: `${width} / ${height}` }}>
        {picture("before", true)}{picture("after", true)}
      </div>
    </> : <div class="diff-image-pair">{(["before", "after"] as const).map(side => <figure key={side}>
      <figcaption>{caption(side)}</figcaption><div class="diff-image-canvas"
        style={width && height ? { width: `${width}px`, aspectRatio: `${width} / ${height}` } : undefined}>{picture(side)}</div>
    </figure>)}</div>}
  </section>;
}
