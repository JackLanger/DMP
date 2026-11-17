import assert from "node:assert/strict";
import { test } from "node:test";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { JSDOM } from "jsdom";
import { act, createElement } from "react";
import { createServer } from "vite";
import init, { greet as rustGreet } from "../rust/pkg/dmp_frontend_core.js";

await init({ module_or_path: await readFile(new URL("../rust/pkg/dmp_frontend_core_bg.wasm", import.meta.url)) });
const greetFromWasm = async (name) => rustGreet(name);

for (const project of ["dmp.server", "dmp.xclient"]) {
  test(`shared frontend works with ${project}`, async () => {
    const root = fileURLToPath(new URL(`../../${project}/`, import.meta.url));
    const server = await createServer({
      root,
      server: { middlewareMode: true },
      appType: "custom",
    });
    const dom = new JSDOM("<div id='root'></div>");
    globalThis.window = dom.window;
    globalThis.document = dom.window.document;
    globalThis.IS_REACT_ACT_ENVIRONMENT = true;

    try {
      const { createRoot } = await import("react-dom/client");
      const { Counter, formatCount } = await server.ssrLoadModule("@dmp/frontend");
      const { ServerView } = await server.ssrLoadModule("@dmp/frontend/server");
      const { DesktopView } = await server.ssrLoadModule("@dmp/frontend/desktop");
      assert.equal(formatCount(3), "Count is 3");

      const container = document.getElementById("root");
      const reactRoot = createRoot(container);
      try {
        await act(() => reactRoot.render(createElement(Counter)));
        const button = container.querySelector("button");
        assert.equal(button.textContent, "Count is 0");
        await act(() => button.click());
        assert.equal(button.textContent, "Count is 1");

        await act(() => reactRoot.render(createElement(ServerView, { greet: greetFromWasm })));
        assert.equal(container.querySelector("h1").textContent, "Get started");
        const serverCounter = container.querySelector("button");
        await act(() => serverCounter.click());
        assert.equal(serverCounter.textContent, "Count is 1");
        assert.match(container.querySelector("use").getAttribute("href"), /#documentation-icon$/);

        for (const View of [ServerView, DesktopView]) {
          await act(() => reactRoot.render(createElement(View, { greet: greetFromWasm })));
          const input = container.querySelector("#greet-input");
          const setValue = Object.getOwnPropertyDescriptor(dom.window.HTMLInputElement.prototype, "value").set;
          await act(() => {
            setValue.call(input, "Zoë 世界");
            input.dispatchEvent(new dom.window.Event("input", { bubbles: true }));
          });
          await act(async () => {
            container.querySelector("form").dispatchEvent(new dom.window.Event("submit", { bubbles: true, cancelable: true }));
          });
          assert.match(container.textContent, /Hello, Zoë 世界! You've been greeted from Rust!/);
        }

        const names = [];
        const greet = async (name) => {
          names.push(name);
          if (names.length === 2) throw new Error("offline");
          return `Hello ${name}`;
        };
        await act(() => reactRoot.render(createElement(DesktopView, { greet })));
        const input = container.querySelector("#greet-input");
        const setValue = Object.getOwnPropertyDescriptor(dom.window.HTMLInputElement.prototype, "value").set;
        await act(() => {
          setValue.call(input, "Ada");
          input.dispatchEvent(new dom.window.Event("input", { bubbles: true }));
        });
        const form = container.querySelector("form");
        await act(async () => {
          form.dispatchEvent(new dom.window.Event("submit", { bubbles: true, cancelable: true }));
        });
        assert.deepEqual(names, ["Ada"]);
        assert.match(container.textContent, /Hello Ada/);
        await act(async () => {
          form.dispatchEvent(new dom.window.Event("submit", { bubbles: true, cancelable: true }));
        });
        assert.match(container.querySelector("[role=alert]").textContent, /offline/);
        assert.doesNotMatch(container.textContent, /Hello Ada/);
      } finally {
        await act(() => reactRoot.unmount());
      }
    } finally {
      dom.window.close();
      delete globalThis.window;
      delete globalThis.document;
      delete globalThis.IS_REACT_ACT_ENVIRONMENT;
      await server.close();
    }
  });
}
