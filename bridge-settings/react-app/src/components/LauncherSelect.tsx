import React from "react";

interface LauncherOption {
  label: string;
  component: string;
}

interface LauncherSelectProps {
  launchers: LauncherOption[];
  current: string;
  onChange: (component: string) => void;
}

export const LauncherSelect: React.FC<LauncherSelectProps> = ({
  launchers,
  current,
  onChange,
}) => {
  if (launchers.length === 0) {
    return <span className="status-display">No launchers found</span>;
  }

  return (
    <select
      className="setting-select"
      value={current}
      onChange={(e) => onChange(e.target.value)}
    >
      {!current && <option value="">Select a launcher...</option>}
      {launchers.map((launcher) => (
        <option key={launcher.component} value={launcher.component}>
          {launcher.label}
        </option>
      ))}
    </select>
  );
};
