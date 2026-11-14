# DMP xclient

## Build in a devcontainer

Install Docker and a devcontainer-compatible editor, such as VS Code with the
[Dev Containers extension](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.remote-containers).
Open the repository root in the editor and select **Dev Containers: Reopen in Container**.
The container installs the Linux Tauri prerequisites, Rust, Node.js, and the
frontend dependencies. Its `node_modules` volume is separate from the host's.

The applications and shared frontend are npm workspaces. For local development,
run `npm ci` in `Portal` first; the root `Portal/package-lock.json` is the
workspace lockfile. See `Portal/README.md` for shared code and build commands.
Local builds also require Rust and the `wasm32-unknown-unknown` target.

In the container terminal, build the executable with:

```sh
npm run tauri -- build --no-bundle
```

The binary is written to `src-tauri/target/release/dmpxclient`. To also build
a Debian package, run `npm run tauri -- build --bundles deb`; the package is
written under `src-tauri/target/release/bundle/deb`.

This container builds Linux artifacts, even on a Windows or macOS host. Building
a native Windows or macOS application requires the corresponding platform and
its Tauri prerequisites. Running the desktop application or `tauri dev` also
requires a graphical display, which this build-focused container does not set up.

## Recommended IDE Setup

- [VS Code](https://code.visualstudio.com/) + [Tauri](https://marketplace.visualstudio.com/items?itemName=tauri-apps.tauri-vscode) + [rust-analyzer](https://marketplace.visualstudio.com/items?itemName=rust-lang.rust-analyzer)
