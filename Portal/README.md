# DMP Portal

The server UI, Tauri client, and shared React/TypeScript frontend are npm
workspaces. From this directory, install all dependencies with `npm ci` and
build both applications with `npm run build`. Install Rust via rustup and add
the WebAssembly target with `rustup target add wasm32-unknown-unknown` first.
The workspace lockfile is `Portal/package-lock.json`; the older
application-level lockfiles are not used for workspace installs. Run `npm test`
to check the shared frontend through both applications' Vite configurations.

Run either UI with `npm run dev --workspace @dmp/server` or
`npm run dev --workspace @dmp/xclient`. For the desktop application, use
`npm run tauri --workspace @dmp/xclient -- dev` instead. The applications can
also run their npm scripts from their own directories after the workspace
install.

Put reusable React components, views, and platform-independent TypeScript in
`dmp.frontend/src`, export them from `src/index.ts`, and import them from
`@dmp/frontend` in either application. The workspace links the source directly,
so changes are available to both Vite development servers without a separate
library build. Keep Tauri-specific code in the desktop application and pass
platform-specific behavior to shared components as props.

Shared Rust code lives in `dmp.frontend/rust`. Export browser-compatible
functions with `wasm-bindgen` and expose them through
`@dmp/frontend/rust`. Both applications run the same WebAssembly module in
their webview or browser; the web server serves the module as an asset, rather
than executing the Rust code. WebAssembly has browser sandbox limitations, so
operations requiring native OS access still need a separate Tauri command and
a browser-compatible alternative. Development and build scripts compile the
module automatically. After changing Rust code while a dev server is running,
run `npm run build:wasm --workspace @dmp/frontend` and reload the page.

The shared `ServerView` and `DesktopView` own their UI and assets. Each
application imports its view from `@dmp/frontend/server` or
`@dmp/frontend/desktop` and its stylesheet from `@dmp/frontend/server.css` or
`@dmp/frontend/desktop.css`, respectively. The separate stylesheet imports
avoid applying both global themes at once. Both entry points supply the shared
WebAssembly greeting implementation.
