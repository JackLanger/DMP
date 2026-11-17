import { useState } from "react";

export type Greet = (name: string) => Promise<string>;

export function GreetingForm({ greet }: { greet: Greet }) {
  const [greetMsg, setGreetMsg] = useState("");
  const [error, setError] = useState("");
  const [name, setName] = useState("");

  async function submitGreeting() {
    try {
      setGreetMsg(await greet(name));
      setError("");
    } catch (reason) {
      setGreetMsg("");
      setError(`Could not greet: ${String(reason)}`);
    }
  }

  return (
    <>
      <form
        className="row"
        onSubmit={(event) => {
          event.preventDefault();
          void submitGreeting();
        }}
      >
        <input
          aria-label="Name"
          id="greet-input"
          onChange={(event) => setName(event.currentTarget.value)}
          placeholder="Enter a name..."
        />
        <button type="submit">Greet</button>
      </form>
      <p>{greetMsg}</p>
      {error && <p role="alert">{error}</p>}
    </>
  );
}
