import { useId, useState } from "react";
export default function FileDropZone({
  files,
  onChange,
  accept,
  multiple = false,
  disabled = false,
  label = "File",
}: {
  files: File[];
  onChange: (files: File[]) => void;
  accept?: string;
  multiple?: boolean;
  disabled?: boolean;
  label?: string;
}) {
  const id = useId(),
    [over, setOver] = useState(false);
  function choose(list: FileList | null) {
    if (!disabled && list)
      onChange(multiple ? Array.from(list) : Array.from(list).slice(0, 1));
  }
  return (
    <div
      className={"file-drop " + (over ? "dragging" : "")}
      onDragOver={(e) => {
        e.preventDefault();
        if (!disabled) setOver(true);
      }}
      onDragLeave={() => setOver(false)}
      onDrop={(e) => {
        e.preventDefault();
        setOver(false);
        choose(e.dataTransfer.files);
      }}
    >
      <input
        id={id}
        className="file-picker"
        aria-label={label}
        type="file"
        accept={accept}
        multiple={multiple}
        disabled={disabled}
        onChange={(e) => choose(e.target.files)}
      />
      <label htmlFor={id}>
        <svg
          viewBox="0 0 24 24"
          width="32"
          height="32"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.5"
          aria-hidden="true"
        >
          <path d="M12 16V3m-5 5 5-5 5 5M4 15v5h16v-5" />
        </svg>
        <strong>
          {files.length === 1
            ? files[0].name
            : files.length
              ? `${files.length} files selected`
              : multiple
                ? "Drop files here, or choose files"
                : "Drop a file here, or choose a file"}
        </strong>
        <span>
          {files.length ? "Choose again to replace" : "Supported files only"}
        </span>
      </label>
    </div>
  );
}
