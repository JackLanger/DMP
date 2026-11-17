import { useState } from "react";
import { formatCount } from "./formatCount";

export function Counter({ className }: { className?: string }) {
  const [count, setCount] = useState(0);

  return (
    <button className={className} type="button" onClick={() => setCount(count + 1)}>
      {formatCount(count)}
    </button>
  );
}
