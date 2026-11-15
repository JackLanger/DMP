# UI Extensibility Guide

This guide describes how to add UI features to the DMP Portal and where each
piece belongs. The portal uses React and TypeScript in an npm workspace with a
shared frontend package and two host applications: a browser/server UI and a
Tauri desktop client.

## How UI composition works

The UI is composed explicitly with React imports and JSX. There is no automatic
feature discovery, plugin registry, route registry, or component auto-loading:
a feature appears only after a component is imported and rendered by a view or
application entry point.

```text
Browser entry: dmp.server/src/main.ts
  -> @dmp/frontend/server (ServerView)
       -> shared components

Tauri entry: dmp.xclient/src/main.tsx
  -> dmp.xclient/src/App.tsx
       -> dmp.xclient/src/FileServerView.tsx (current screen)
```

`dmp.frontend` is the place for reusable, platform-independent UI. Each host
application owns its entry point and any behavior that requires its platform.
The shared package exposes selected files through `dmp.frontend/package.json`.

## Where to put a change

| Change | Put it here | Then connect it here |
| --- | --- | --- |
| Reusable React component or view | `dmp.frontend/src/` | Import it into `ServerView`, `DesktopView`, or a host app. Export it if consumers need a package import. |
| Browser/server-only UI | `dmp.server/src/` | Add it to the active server app or its entry point. |
| Tauri-only UI or Tauri API integration | `dmp.xclient/src/` | Render it from `App.tsx` or the current desktop screen. |
| Shared server theme/styles | `dmp.frontend/src/styles/server.css` | The server entry imports `@dmp/frontend/server.css`. |
| Shared desktop theme/styles | `dmp.frontend/src/styles/desktop.css` | Import `@dmp/frontend/desktop.css` from the desktop app when using that theme. |
| Styles specific to a host feature | Alongside that feature, e.g. `dmp.xclient/src/fileserver.css` | Import the stylesheet from the feature component. |
| Shared static assets | `dmp.frontend/src/assets/` | Import the asset from the component that uses it. |
| Tauri-only static assets | `dmp.xclient/src/assets/` | Import them in the desktop app. |
| Browser-compatible Rust/WASM behavior | `dmp.frontend/rust/` and `dmp.frontend/src/rust.ts` | Export a wasm-bindgen function and expose a TypeScript wrapper. |
| Native OS capability | `dmp.xclient/src-tauri/src/` | Add a Tauri command/plugin and call it from desktop-only frontend code. |

## Add a reusable shared feature

1. Create a component under `dmp.frontend/src/`, for example
   `dmp.frontend/src/StatusPanel.tsx`. Keep it independent of browser-server
   and Tauri APIs. Pass behavior or data it needs through typed props.
2. Add any feature-specific styles to an appropriate shared stylesheet or a
   colocated CSS file imported by the component. Use feature-prefixed class
   names to avoid collisions with global styles.
3. Import and render the component from the view that should contain it:
   `dmp.frontend/src/ServerView.tsx`, `dmp.frontend/src/DesktopView.tsx`, or
   the host application's current view.
4. If another workspace package needs to import the component directly, export
   it through the package API. `dmp.frontend/src/index.ts` is the root export
   surface (`@dmp/frontend`). For a dedicated entry point, add a matching
   subpath to the `exports` map in `dmp.frontend/package.json`, following the
   existing `./server`, `./desktop`, `./rust`, and CSS entries.
5. Update or add tests under `dmp.frontend/test/` when the behavior is
   testable, then run the checks described below.

For example, a component used internally by `ServerView` can remain a local
module import. A component imported from `@dmp/frontend/status-panel` needs an
export-map entry for `./status-panel`; merely adding the source file does not
make that package path available.

### Shared view composition example

```tsx
import { StatusPanel } from "./StatusPanel";

export function ServerView() {
  return (
    <main>
      <h1>Server</h1>
      <StatusPanel status="ready" />
    </main>
  );
}
```

The existing views also receive platform behavior via props: `ServerView` and
`DesktopView` accept a `greet` function. Follow that pattern when shared UI
needs host-specific behavior, rather than importing a Tauri API into
`dmp.frontend`.

## Add a browser/server feature

The browser app mounts `ServerView` from `dmp.server/src/main.ts`. That file
also imports `@dmp/frontend/server.css`; the package maps that path to the
shared server stylesheet. To add UI to the browser/server screen, either:

- implement a shared component in `dmp.frontend/src/` and render it from
  `ServerView`; or
- implement a server-only component under `dmp.server/src/` and render it from
  the server entry/view.

Keep browser-only work in the server application. The server view currently
lives in the shared package, so importing a server-only component from it would
break the shared-package boundary. In that case, place the server-specific
composition in `dmp.server/src/` and make the entry point render that host view
instead.

## Add a Tauri desktop feature

The Tauri React entry mounts `App` from `dmp.xclient/src/main.tsx`. Today,
`dmp.xclient/src/App.tsx` renders `FileServerView` from
`dmp.xclient/src/FileServerView.tsx`, which imports its own
`fileserver.css`. Add a desktop-only screen or feature to the `App` composition
(or to the active screen component), then style it with a colocated stylesheet
or a desktop theme.

`dmp.frontend/src/DesktopView.tsx` is a shared view and is exposed as
`@dmp/frontend/desktop`, but the current Tauri `App` does not render it. Adding
content to `DesktopView` alone will therefore not change the current Tauri
screen. If the intended screen is the shared `DesktopView`, explicitly wire it
into `App.tsx` and provide its required `greet` prop.

Tauri commands, filesystem dialogs, and native OS behavior belong in
`dmp.xclient/src/` and `dmp.xclient/src-tauri/`. Keep shared components free of
`@tauri-apps/*` imports; provide callbacks or adapter props from the desktop
host instead. This lets browser/server consumers use the same component
without pulling in native-only functionality.

## Exports and imports

The shared frontend is an npm workspace package named `@dmp/frontend`. Its
`exports` map in `dmp.frontend/package.json` is the supported package boundary:

- `@dmp/frontend` -> `dmp.frontend/src/index.ts`
- `@dmp/frontend/server` -> `dmp.frontend/src/ServerView.tsx`
- `@dmp/frontend/desktop` -> `dmp.frontend/src/DesktopView.tsx`
- `@dmp/frontend/rust` -> `dmp.frontend/src/rust.ts`
- `@dmp/frontend/server.css` and `@dmp/frontend/desktop.css` -> shared themes

Use package imports from either application. Use relative imports within the
shared package. For any new public package path, add and maintain the matching
`exports` entry; do not rely on consumers reaching into an unexported source
file. The workspace links the package source directly, so no separate frontend
library build is needed for normal application development.

## Styling and assets

- Global theme rules belong in `dmp.frontend/src/styles/server.css` or
  `desktop.css`. These styles include global element selectors and theme
  variables, so avoid importing both themes into one app unless that is
  intentional.
- Feature-specific styles can be colocated with the feature and imported by
  its TSX file, as `FileServerView.tsx` does with `fileserver.css`.
- Prefer classes scoped to the feature (for example,
  `.status-panel__message`) over broad selectors such as `button` or `section`.
- Put shared assets in `dmp.frontend/src/assets/` and import them in code.
  Keep assets used only by one application in that application's `src/assets/`.

## Platform and data boundaries

Shared React code can run in both the browser and the Tauri webview. Keep it to
browser-compatible TypeScript and web APIs. For host-specific behavior, define
an interface/callback prop in the shared component and implement it in each
host that uses the component. Do not assume a Tauri command exists in the
browser app.

The Rust code in `dmp.frontend/rust/` is compiled to WebAssembly and is
available to both frontends. It runs in the browser sandbox; native OS access
still requires a Tauri command and a browser-compatible alternative if the
feature is shared.

## Validation

From `Portal/`:

```sh
npm test
npm run build
```

`npm test` runs the shared frontend checks, including its configured WASM
pretest build. `npm run build` builds both server and desktop applications.
For a faster host-specific build, use
`npm run build --workspace @dmp/server` or
`npm run build --workspace @dmp/xclient`.

## Extension checklist

- [ ] The feature is in the shared package only if it is platform-independent.
- [ ] The component is rendered from the correct active view/app.
- [ ] New package consumers use an explicit export-map path.
- [ ] Tauri APIs and native commands stay in the desktop host.
- [ ] Styles and assets are scoped to the feature or appropriate host/theme.
- [ ] Tests, both app builds, and this guide are updated where needed.
