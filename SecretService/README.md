# SecretService

SecretService is a Go/Gin account and token service and the authenticated
gateway to FileServer. It validates each file request and proxies it to the
private FileServer origin. File-level access controls are not implemented,
so all authenticated users share access to all files and transfers.

## Running

Use Go 1.24 or newer. Supply a certificate and private key for the host name
clients use, and configure two distinct random secrets of at least 32 characters.
Do not expose these values to untrusted clients. For example, with trusted
certificates already provisioned:

For a local deployment, generate a new private CA and a server certificate:

```sh
bash scripts/create-secretservice-certs.sh "$HOME/secretservice-certs" \
  DNS:secrets.example.test IP:192.0.2.10
```

Run this from the repository root, and replace the example names with the
hostnames and IP addresses clients actually use. The additional names are
optional; `secretservice`, `localhost`, `127.0.0.1`, and `::1` are always
included. The output directory must not already exist. Set `SECRET_TLS_CERT`
and `SECRET_TLS_KEY` to the printed paths; configure the deployment's
`SECRET_SERVICE_CA` with the printed CA certificate path. This script does
not generate secrets or change existing configuration.

The server certificate expires after one year; the CA expires after ten years.
Keep both private keys private and back up the CA key securely. Trust `ca.crt`
on each client via a trusted channel after verifying its fingerprint; trusting
a CA also permits it to authenticate other sites. The directory and keys are
private to the generating user. For Docker, provision the server certificate
and key so the container's UID 65532 can read them, without exposing the CA
private key. Do not commit any generated files to the repository.

The service automatically reads `SecretService/.env` when started from the
repository root, or `.env` in its working directory otherwise (including when
started from `SecretService`). Configure your IDE working directory accordingly.
The file is optional; environment variables already present in the process take
precedence, including empty values. Keep `.env` private and use absolute paths
for the certificate, key, and database if starting from different directories.
An unreadable or malformed `.env` stops startup. Alternatively, set the
variables directly:

```sh
export SECRET_ADMIN_SECRET='...'
export SECRET_SERVICE_SECRET='...'
export SECRET_TLS_CERT=/path/to/server.crt
export SECRET_TLS_KEY=/path/to/server.key
export SECRET_DATABASE_PATH=/path/to/persistent/secret.db
export FILESERVER_UPSTREAM_URL=http://127.0.0.1:8080
cd SecretService
go run ./cmd/secretservice
```

Pushes to `main` publish AMD64 and ARM64 container images to
`ghcr.io/jacklanger/dmp/secretservice` with `latest` and commit-SHA tags.
See [FileServer's deployment instructions](../FileServer/README.md) for
Compose configuration and registry authentication.

The default listen address is `:8443`; set `SECRET_LISTEN_ADDRESS` to change it.
`SECRET_ALLOWED_ORIGINS` is an optional comma-separated list of exact browser
origins. Use a persistent database directory with restricted permissions,
including for backups, and protect the private key. The server requires TLS;
clients must verify its certificate. The container runs as a non-root user and
requires writable database storage and readable TLS files.

`FILESERVER_UPSTREAM_URL` is required and must be a fixed HTTP(S) origin without
credentials, path or query. Use HTTP only on a trusted private network; HTTPS
uses the system trust store. FileServer does not authenticate requests:
firewall or otherwise isolate all its listeners from clients and untrusted
containers. Docker's internal network does not isolate it from the host or
other members. Never expose FileServer directly. Requests to `/` and `/files`
through SecretService require the same `X-User` and bearer-token headers as
group endpoints; user credentials are not forwarded to FileServer. The gateway
returns 401 for invalid credentials, 503 for validation failures, and 502
when the upstream is unavailable. Token revocation affects subsequent requests,
not an already-running transfer.

Browser clients use `SECRET_ALLOWED_ORIGINS` for both account and file APIs,
including multipart uploads, resumable transfers and range downloads. The
gateway streams file bodies without the 8 KiB limit used for its JSON APIs.
Long transfers are not subject to a fixed total request or response timeout;
limit concurrent connections and enforce suitable idle-transfer timeouts at
the ingress for your deployment.

## HTTP API

Requests and responses use JSON over HTTPS. Admin provisioning uses
`X-Admin-Secret`; `POST /v1/accounts` accepts `username` and `password`, and
returns `userId`. Usernames consist of 3–64 ASCII letters, digits, underscores,
periods or hyphens, beginning with a letter or digit; comparisons are
case-insensitive. Passwords must be 12–72 bytes.

For example, create a private `admin-headers.txt` containing the line
`X-Admin-Secret: <administrator secret>` and a private `account.json`
containing `{"username":"alice","password":"<user password>"}`, then run:

```sh
curl --cacert /path/to/ca.crt -H @admin-headers.txt \
  -H 'Content-Type: application/json' --data-binary @account.json \
  https://<secretservice-host>:8443/v1/accounts
```

Replace the placeholders before running the command, restrict the files to the
administrator, and securely remove them when no longer needed. The hostname
must match the server certificate. To sign in as that user, send the same JSON
to `/v1/login` without the admin header. Protect the returned tokens and send the
access token and username to the gateway as described in FileServer's README.

`POST /v1/login` accepts `username` and `password`. Its response contains
`accessToken`, `refreshToken`, `accessExpiresAt` and `refreshExpiresAt`. Access
tokens expire after 15 minutes; refresh tokens expire after 30 days.
`POST /v1/refresh` accepts `username` and the refresh token in `token`; each
successful request rotates the refresh token and restarts its 30-day lifetime.
Concurrent refreshes using the same token have only one successful result.
`POST /v1/logout` accepts the same payload and revokes the associated session
and its access tokens. Never put tokens in URLs or logs.

The service-only `POST /v1/introspect` uses `X-Service-Secret` and accepts
`username` and an access token in `token`. A successful response contains
`userId`, canonical `username`, and current `groupIds`. Invalid or expired
tokens receive 401. The service credential must remain private to trusted
services. Both tokens are stored as hashes rather than plaintext.

Group endpoints require `X-User: <username>` and
`Authorization: Bearer <accessToken>`. `GET /v1/groups` lists memberships;
`POST /v1/groups` with `{"name":"..."}` creates a group and makes the creator
its administrator. `GET /v1/groups/{id}/members` lists its members. Only the
group administrator may add or remove a member via `PUT` or `DELETE
/v1/groups/{id}/members/{username}`; the administrator cannot remove
themselves. Membership changes are reflected in subsequent introspection.

The login, refresh, logout and account-provisioning endpoints have an in-process
per-source limit of 30 requests per minute. Configure a separate ingress limit
for multiple instances or when a reverse proxy aggregates clients, and restrict
access to administrator and service credentials and the introspection endpoint.

## Verification

```sh
go test -race ./...
go vet ./...
```
