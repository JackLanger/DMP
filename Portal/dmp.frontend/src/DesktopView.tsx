import reactLogo from "./assets/react.svg";
import tauriLogo from "./assets/tauri.svg";
import viteLogo from "./assets/vite.svg";
import { Counter } from "./Counter";
import { GreetingForm } from "./GreetingForm";
import type { Greet } from "./GreetingForm";

export function DesktopView({ greet }: { greet: Greet }) {
  return (
    <main className="container">
      <h1>Welcome to Tauri + React</h1>
      <div className="row">
        <a href="https://vite.dev" target="_blank" rel="noreferrer">
          <img src={viteLogo} className="logo vite" alt="Vite logo" />
        </a>
        <a href="https://tauri.app" target="_blank" rel="noreferrer">
          <img src={tauriLogo} className="logo tauri" alt="Tauri logo" />
        </a>
        <a href="https://react.dev" target="_blank" rel="noreferrer">
          <img src={reactLogo} className="logo react" alt="React logo" />
        </a>
      </div>
      <p>Click on the Tauri, Vite, and React logos to learn more.</p>
      <Counter />
      <GreetingForm greet={greet} />
    </main>
  );
}
