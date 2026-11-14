import { DesktopView } from "@dmp/frontend/desktop";
import { greet } from "@dmp/frontend/rust";
import "@dmp/frontend/desktop.css";

function App() {
  return <DesktopView greet={greet} />;
}

export default App;
