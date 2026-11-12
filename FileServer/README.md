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

The HTTP server uses WebFlux and the reactive MongoDB driver for uploads.
Multipart file transfer is reactive; ZIP compression and filesystem cleanup run
on bounded-elastic worker threads because those operations use blocking I/O.
The existing synchronous MongoDB reads and archive downloads also run off the
event loop. `ARCHIVE_UPLOAD_DIR` selects the archive directory.

Uploads require `file` and JSON `metadata` multipart parts. The HTTP server
requires authentication and CSRF protection; restart it after configuration
changes. The former SOAP starter was removed because it selected Spring MVC;
there are currently no SOAP endpoints in FileServer.
