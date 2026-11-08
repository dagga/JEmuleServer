'use client';

import { useEffect, useRef, useState, type ReactNode } from 'react';

/**
 * A modal confirmation, with an optional text field (a reason): onConfirm gets its value.
 */
export function ConfirmDialog({
  open, title, children, inputLabel, inputPlaceholder, confirmLabel, danger, onConfirm, onCancel,
}: {
  open: boolean;
  title: string;
  children?: ReactNode;
  inputLabel?: string;
  inputPlaceholder?: string;
  confirmLabel: string;
  danger?: boolean;
  onConfirm: (value: string) => void;
  onCancel: () => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const [value, setValue] = useState('');

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) {
      setValue('');
      dialog.showModal();
    } else if (!open && dialog.open) {
      dialog.close();
    }
  }, [open]);

  return (
    <dialog ref={ref} className="dialog" onCancel={(e) => { e.preventDefault(); onCancel(); }}>
      <form
        method="dialog"
        onSubmit={(e) => {
          e.preventDefault();
          onConfirm(value.trim());
        }}
      >
        <h2>{title}</h2>
        {children && <div className="dialogBody">{children}</div>}
        {inputLabel && (
          <label className="field">
            <span>{inputLabel}</span>
            <input autoFocus value={value} maxLength={240} placeholder={inputPlaceholder} onChange={(e) => setValue(e.target.value)} />
          </label>
        )}
        <div className="dialogActions">
          <button type="button" className="btn" onClick={onCancel}>Cancel</button>
          <button type="submit" className={'btn ' + (danger ? 'btnDanger' : 'btnPrimary')}>{confirmLabel}</button>
        </div>
      </form>
    </dialog>
  );
}
