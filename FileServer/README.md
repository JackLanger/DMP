# FileServer setup

FileServer authenticates users through [SecretService](../SecretService/README.md).
An administrator provisions user accounts there; the old `dev` user, HTTP
Basic and Spring login form are no longer supported. **All authenticated users
currently have access to all files and transfers**; per-user and group file
permissions are not implemented.

Copy `.env.example` to `.env` in this directory and keep it private. Set
`VERSION` to the version in `VERSION`, generate two distinct random secrets
of at least 32 characters for `SECRET_ADMIN_SECRET` and
`SECRET_SERVICE_CREDENTIAL`, and provide the absolute paths for the SecretService
certificate, private key, CA certificate and persistent database directory.
The server certificate must include `secretservice` in its DNS subject
alternative names for Docker and the hostname or IP clients use to reach it.
The SecretService container runs as UID 65532: the database directory must be
writable and the certificate and private key readable by that user. Restrict
the permissions and back up the account database securely.

Build the FileServer JAR, then run `docker compose up -d --build` from this
directory. This builds the Go SecretService image locally. The FileServer
container checks the SecretService certificate against `SECRET_SERVICE_CA`.
For direct runs, configure `SECRET_SERVICE_URL` (an HTTPS URL),
`SECRET_SERVICE_CREDENTIAL`, and optionally `SECRET_SERVICE_CA` (a PEM CA
certificate; otherwise the JVM trust store is used). From this directory, run
`./gradlew bootRun`; Gradle reads these values from `.env` when not already set
in the environment. `SECRET_SERVICE_URL` must match the certificate hostname.

Users sign in directly to SecretService using `POST /v1/login`; each FileServer
request must include `X-User: <username>` and
`Authorization: Bearer <accessToken>`. Access tokens last 15 minutes; refresh
and logout are provided by SecretService. FileServer checks every request
online and responds with 401 for invalid credentials or 503 if validation is
unavailable. The service credential is private and is never sent by users.
Use HTTPS to both services outside a trusted local environment.

## Published container

Pushes to `main` build and test FileServer, then publish AMD64 and ARM64 images
to `ghcr.io/jacklanger/dmp/fileserver` with `latest` and commit-SHA tags. The
workflow publishes the image only; it does not automatically update a running
server. Package visibility is managed in GitHub Packages. If the package is
private, authenticate Docker to `ghcr.io` with a GitHub token that can read
the package before pulling.

Compose can use the published FileServer image by setting
`FILESERVER_IMAGE_TAG`, but the SecretService image must currently be built
locally from the repository before using `--no-build`. A private GitHub package
also requires Docker authentication to `ghcr.io`. Keep the persistent data and
Caddy volumes when updating.

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
reservation, and set `FILESERVER_HTTPS_HOST` in `.env` to that IP
address or a local hostname resolving to it. Do not include a scheme or port.
If you use a hostname, configure DNS or hosts files on each client.

Set `FILE_SERVER_DATA` to an appropriate persistent host directory for the
database and archives; if unset, it defaults to `./FileServer/data` relative to
this directory.

Provision the SecretService image, certificates, persistent data and `.env` on
the target host. Run from the directory containing both Compose files:

```sh
docker compose -f compose.yaml -f compose.https.yaml up -d --no-build
```

Compose pulls the FileServer and Caddy images as needed. This override requires
Docker Compose 2.24.4 or newer for `!reset` and inline configs. It removes the
host port mapping for FileServer and exposes Caddy on ports 80 and 443 instead.
Use `https://<FILESERVER_HTTPS_HOST>` from clients; port 80 redirects to HTTPS.
Ensure those ports are available and restrict network access with the host
firewall as appropriate.

Caddy stores its CA and certificates in the `caddy_data` Docker volume. After
startup, export its **public root certificate** on the Pi:

```sh
docker compose -f compose.yaml -f compose.https.yaml exec -T caddy \
  cat /data/caddy/pki/authorities/local/root.crt > fileserver-local-root.crt
```

Transfer this certificate to each client over a trusted channel, verify its
fingerprint, and install it in the trust store used by that client or browser.
To obtain the SHA-256 fingerprint on the Pi, run:

```sh
openssl x509 -in fileserver-local-root.crt -noout -fingerprint -sha256
```

Communicate that fingerprint to each client separately over a trusted channel.
Copy `scripts/install-fileserver-ca.sh` to Linux or macOS clients, or
`scripts/install-fileserver-ca.ps1` to Windows clients, along with the public
certificate. From the directory containing the copied files, use the fingerprint
obtained from the Pi (not one supplied with an untrusted certificate):

```sh
# Fedora/RHEL, Debian/Ubuntu, Arch, openSUSE, or macOS
bash install-fileserver-ca.sh fileserver-local-root.crt 'AA:BB:...'
```

```powershell
# Windows PowerShell, for the current user
.\install-fileserver-ca.ps1 .\fileserver-local-root.crt 'AA:BB:...'
```

Replace `AA:BB:...` with the complete SHA-256 fingerprint. The scripts reject
fingerprint mismatches before changing trust. Linux and macOS installations
require administrator privileges; Windows installs into the current user's root
store. Restart the browser if needed. Some browsers may use a separate trust
store and require importing the certificate there too. Trusting a CA allows it
to authenticate other sites as well, so only install a CA you control.

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
requires the user and bearer token headers; cookie sessions and CSRF tokens
are not used. Multipart file parts are limited to 100 MiB by default; set
`FILESERVER_MULTIPART_MAX_DISK_USAGE_PER_PART` (for example, `1GB`) to change
the limit. Compose and Gradle `bootRun` also read it from
`.env`; set it in the run configuration for direct IDE runs. The
disk limit must be at least the multipart in-memory threshold (256 KiB by
default). Multipart requests exceeding a size limit receive HTTP 413.
The former SOAP starter was removed because it selected Spring MVC;
there are currently no SOAP endpoints in FileServer.

CORS is enabled for the local Vite origin and Tauri desktop origins by default.
For another frontend origin, set `FILESERVER_CORS_ALLOWED_ORIGINS` to a
comma-separated list of exact origins (for example,
`https://portal.example.com`). Keep this list limited to trusted frontends.
The portal's download function accepts an access token, but the portal does not
yet provide a sign-in UI or refresh flow. Use the API or scripts until those
client features are implemented. The legacy portal configuration still has a
password field; FileServer no longer uses it.

## Resumable transfers

See [TRANSFER-API.md](../wiki/FileServer/TRANSFER-API.md) for the complete protocol and a Node.js
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
the transfer completes. Set `FILESERVER_USER` and `FILESERVER_ACCESS_TOKEN`
in the environment before running the scripts. Tokens expire after 15 minutes;
if a transfer outlasts its token, obtain a new one and resume with the transfer
ID. The Bash script `bash scripts/create-transfer.sh <file>` requires
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
