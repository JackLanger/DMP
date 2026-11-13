# FileServer MongoDB setup

Copy `.env.example` to `.env` in this directory (or add the missing variables to
your existing `.env`). Set distinct, strong values for `MONGO_PASSWORD` and
`MONGO_APP_PASSWORD`; keep this file private. The application username and
password must use only URI-unreserved characters: letters, digits, `.`, `_`,
`~`, and `-`. The database name may contain only letters, digits, `_`, and
`-`. For example, generate a password with `openssl rand -hex 32`. Set
`VERSION` to the version in `VERSION`.

Set `FILESERVER_PASSWORD` to a separate strong password for the FileServer HTTP
user `dev`. Compose and Gradle `:FileServer:bootRun` pass it to Spring Security;
an explicitly set `SPRING_SECURITY_USER_PASSWORD` takes precedence for
Gradle. For a direct Spring Boot run in the IDE, set
`SPRING_SECURITY_USER_PASSWORD` in the run configuration. If no password is
configured for a local run, Spring Security generates one at startup.

Run `docker compose up -d mongodb` from this directory. On a fresh MongoDB data
directory, `mongo-init.js` creates the application user in `MONGO_DATABASE`
with `readWrite` access to that database only. The root user is for
administration; FileServer connects as the application user.

MongoDB runs initialization scripts only for a fresh data directory. To add
the application user to an **existing** MongoDB container, first update `.env`
and then run the following from this directory:

```sh
docker cp mongo-init.js "$(docker compose ps -q mongodb):/tmp/dmp-mongo-init.js"
docker exec --env-file .env "$(docker compose ps -q mongodb)" sh -c \
  'mongosh --authenticationDatabase admin --username "$MONGO_USER" --password "$MONGO_PASSWORD" /tmp/dmp-mongo-init.js'
```

The root credentials must match those used to initialize the existing data.
The script refuses to overwrite an existing application user; manage that user
separately if its credentials or roles need changing. Do not remove the
existing MongoDB volume to rerun initialization: that would discard its data.

Build the application JAR before running `docker compose up -d --build
fileserver`. Gradle `:FileServer:bootRun` reads the application credentials
from `.env` and connects to `localhost`. An explicitly set
`SPRING_MONGODB_URI` takes precedence. For a direct Spring Boot run in the IDE,
set `SPRING_MONGODB_URI` in its run configuration, using the same application
credentials and database:

```text
mongodb://APP_USER:APP_PASSWORD@localhost:27017/dmp?authSource=dmp
```

For containers, Compose supplies the URI with `mongodb` as the host. Without
`SPRING_MONGODB_URI` or Gradle's `.env` configuration, the local application
defaults to an unauthenticated `mongodb://localhost:27017/dmp`, suitable only
for a MongoDB instance that does not require authentication. MongoDB databases
normally appear after their first write.

## Uploads

The HTTP server uses WebFlux and the reactive MongoDB driver for file metadata
reads and uploads. Multipart transfers and downloads are streamed reactively;
blocking ZIP and filesystem operations run off the event loop.
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

## Resumable transfers

See [TRANSFER-API.md](TRANSFER-API.md) for the complete protocol and a Node.js
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
