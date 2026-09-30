import type { JSX } from "preact";
import { COLUMN_LABELS, isColumnType } from "../lib/workspace.ts";
import type { ColumnType } from "../lib/workspace.ts";

export interface ColumnHeaderProps {
  type: ColumnType;
  types: readonly ColumnType[];
  canAdd: boolean;
  canClose: boolean;
  onType: (type: ColumnType) => void;
  onAdd: () => void;
  onClose: () => void;
}

export function ColumnHeader({ type, types, canAdd, canClose, onType, onAdd, onClose }: ColumnHeaderProps) {
  const label = COLUMN_LABELS[type];
  const change = (event: JSX.TargetedEvent<HTMLSelectElement>) => {
    const next = event.currentTarget.value;
    if (isColumnType(next)) onType(next);
  };
  return (
    <div class="column-header">
      <select class="column-type" aria-label={label + " column type"} value={type} onChange={change}>
        {types.map((option) => (
          <option key={option} value={option} selected={option === type}>{COLUMN_LABELS[option]}</option>
        ))}
      </select>
      <button type="button" class="icon-button icon-button-small column-add" disabled={!canAdd}
              aria-label={"Add a column beside " + label} title="Add a column" onClick={onAdd}>⊞</button>
      <button type="button" class="icon-button icon-button-small column-close" disabled={!canClose}
              aria-label={"Close the " + label + " column"} title="Close column" onClick={onClose}>×</button>
    </div>
  );
}
