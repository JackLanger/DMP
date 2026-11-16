# FileServer setup

Copy `.env.example` to `.env` in this directory (or add the missing variables to
your existing `.env`). Set `VERSION` to the version in `VERSION` and set
`FILESERVER_PASSWORD` to a strong password for the FileServer HTTP user `dev`.
Keep `.env` private. Compose and Gradle `:FileServer:bootRun` pass the password
to Spring Security; an explicitly set `SPRING_SECURITY_USER_PASSWORD` takes
precedence for Gradle. For a direct IDE run, set
`SPRING_SECURITY_USER_PASSWORD` in the run configuration. Never use the default
local password in production.

Build the application JAR before running `docker compose up -d --build
fileserver` from this directory.

Metadata is stored in SQLite at `data/metadata.db` relative to the application
working directory. For a direct run, set the `FILESERVER_DATABASE_PATH`
environment variable to use another file; the parent directory is created
automatically. In Compose, the database and archives both live inside
`/app/data`, backed by `FILE_SERVER_DATA` on the host. Use a persistent local
filesystem with permissions for the container, and back up the database together
with the archives while FileServer is stopped so they remain consistent.

The SQLite database starts empty; existing MongoDB metadata is not imported.
Existing archive files are not deleted, but they will not be accessible
through the API without their metadata. Preserve any old MongoDB volumes and
archives if that data may be needed. An old MongoDB container may remain as a
Compose orphan; stop it separately after confirming it is no longer needed.

## HTTPS on a local network

The optional HTTPS override uses Caddy as a reverse proxy with its own local
certificate authority (CA). It leaves the default development Compose setup
unchanged. Use a stable IP address for the Raspberry Pi, such as a DHCP
reservation, and set `FILESERVER_HTTPS_HOST` in `FileServer/.env` to that IP
address or a local hostname resolving to it. Do not include a scheme or port.
If you use a hostname, configure DNS or hosts files on each client.

Set `FILE_SERVER_DATA` to an appropriate persistent host directory for the
database and archives; if unset, it defaults to `./FileServer/data` relative to
this directory.

Build the application JAR first, then run from the `FileServer` directory:

```sh
docker compose -f compose.yaml -f compose.https.yaml up -d --build
```

This override requires Docker Compose 2.24.4 or newer for `!reset`. It removes
the host port mapping for FileServer and exposes Caddy on ports
80 and 443 instead. Use `https://<FILESERVER_HTTPS_HOST>` from clients; port
80 redirects to HTTPS. Ensure those ports are available and restrict network
access with the host firewall as appropriate.

Caddy stores its CA and certificates in the `caddy_data` Docker volume. After
startup, export its **public root certificate** on the Pi:

```sh
docker compose -f compose.yaml -f compose.https.yaml exec -T caddy \
  cat /data/caddy/pki/authorities/local/root.crt > fileserver-local-root.crt
```

Transfer this certificate to each client over a trusted channel, verify its
fingerprint, and install it in the trust store used by that client or browser.
For a one-off check without installing it, use
`curl --cacert fileserver-local-root.crt https://<FILESERVER_HTTPS_HOST>/`.
An HTTP authentication response also confirms the TLS connection succeeded.
Do not distribute the CA private key. Protect and back up the Caddy data
volume: deleting or replacing it changes the CA and requires clients to trust
the new root. Never remove the volumes when stopping the deployment.

If you change from an IP address to a hostname, update
`FILESERVER_HTTPS_HOST`, recreate the stack with the same command, and use
the new hostname in the client. The hostname must resolve on the client; the
existing CA remains trusted as long as its data volume is retained.

## Uploads

The HTTP server uses WebFlux. SQLite metadata operations and blocking ZIP and
filesystem operations run off the event loop; multipart transfers and
downloads are streamed reactively.
`ARCHIVE_UPLOAD_DIR` selects the archive directory, and `ARCHIVE_WORKERS`
controls the number of archive I/O workers.

Archive storage uses ordered `ArchiveStorageStrategy` implementations. The
default strategy stores each file as a single-entry `.zip`; existing archives
remain readable. Additional strategies can select inputs and provide their own
unique filename extension, encoding, content comparison, and decoding. Keep the
ZIP strategy registered when adding formats so previously stored files remain
accessible. The current HTTP upload and download APIs still represent individual
files; directory ingestion and browsing require separate API support.

Uploads require `file` and JSON `metadata` multipart parts. The HTTP server
requires authentication and CSRF protection; restart it after configuration
changes. Multipart file parts are limited to 100 MiB by default; set
`FILESERVER_MULTIPART_MAX_DISK_USAGE_PER_PART` (for example, `1GB`) to change
the limit. Compose and Gradle `:FileServer:bootRun` also read it from
`FileServer/.env`; set it in the run configuration for direct IDE runs. The
disk limit must be at least the multipart in-memory threshold (256 KiB by
default). Multipart requests exceeding a size limit receive HTTP 413.
The former SOAP starter was removed because it selected Spring MVC;
there are currently no SOAP endpoints in FileServer.

The DMP desktop client connects to the FileServer API from its Tauri webview.
CORS is enabled for the local Vite origin and Tauri desktop origins by default.
For another frontend origin, set `FILESERVER_CORS_ALLOWED_ORIGINS` to a
comma-separated list of exact origins (for example,
`https://portal.example.com`). Keep this list limited to trusted frontends; the
client uses HTTP Basic authentication and a CSRF token for uploads.

## Resumable transfers

See [TRANSFER-API.md](../docs/FileServer/TRANSFER-API.md) for the complete protocol and a Node.js
upload and resume example.

For uploads that may be interrupted, create a session with
`POST /files/transfers` and JSON containing `filename`, `size`, `sha256`
(hexadecimal SHA-256 of the complete file), `description`, and `tags`. The
response includes a stable `transferId`, `offset`, `length`, and `expiresAt`.
Persist the transfer ID on the client and query `GET` or `HEAD
/files/transfers/{transferId}` after reconnecting to recover the current
`Upload-Offset`.

To upload a local file, run `.\scripts\create-transfer.ps1 <file>` in
PowerShell (or `powershell -File scripts\create-transfer.ps1 <file>` in cmd).
It creates a session, prints its transfer ID, uploads chunks, and completes
the transfer. To resume an interrupted upload, run the same command with
`-TransferId <id>`; use the same local file, whose checksum is verified when
the transfer completes. The script uses `scripts\fileserver.netrc` for HTTP
credentials. The Bash script `bash scripts/create-transfer.sh <file>` requires
Python 3.8 or later and currently only creates a session; send its chunks and
complete it separately.

Send chunks as `PATCH /files/transfers/{transferId}` with
`Content-Type: application/octet-stream` and a `Content-Range` header such as
`bytes 0-262143/900000`. Chunks must be sequential and at most 256 KiB. A retry
of an already accepted range is acknowledged only when its bytes match the
stored content; conflicting retries and offsets receive `409 Conflict`. Once
the full file is present, `POST /files/transfers/{transferId}/complete`
verifies the declared checksum, stores the archive, and creates metadata with
the transfer ID as its document ID. Repeating completion returns the same
document ID. `DELETE /files/transfers/{transferId}` abandons the session.

Partial chunks and checkpoints are stored under `.transfers` in the archive
directory, so sessions survive FileServer restarts. `ARCHIVE_TRANSFER_EXPIRY`
sets the inactivity lifetime (default `PT24H`); expired sessions are removed
periodically according to `ARCHIVE_TRANSFER_CLEANUP_INTERVAL` (default one
hour). Completed document archives are not removed when their transfer
checkpoint expires.

Downloads from `GET /files/{id}/download` support one HTTP byte range and return
`206 Partial Content`, `Content-Range`, and `Accept-Ranges`. The response ETag
is the SHA-256 of the uncompressed document content, allowing clients to use
`If-Range` when resuming; a changed validator falls back to a full `200`
response. Invalid or unsatisfiable ranges return `416 Range Not Satisfiable`.
