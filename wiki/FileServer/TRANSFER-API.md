# Uploading files with the transfer API

The resumable transfer API separates session creation from uploading. A
successful `201 Created` response means only that the session exists; the file
is not available as a document until the chunks have been uploaded and the
session has been completed.

## Protocol

All requests require authentication. For the default Spring Security setup,
clients making `POST`, `PATCH`, or `DELETE` requests also need the CSRF token
and its associated session cookie. See the example below.

1. **Create a session:** Send `POST /files/transfers` with
   `Content-Type: application/json` and a JSON object containing:
   - `filename`: the file name.
   - `size`: the total file size in bytes.
   - `sha256`: the hexadecimal SHA-256 digest of the entire file.
   - `description`: a description, and `tags`: an array of strings.

   Save the returned `transferId` immediately. The response includes `offset`,
   `length`, `expiresAt`, and `completed`. A new session starts at offset 0.
2. **Upload chunks:** Send sequential `PATCH /files/transfers/{transferId}`
   requests with a binary request body of at most 256 KiB. Set
   `Content-Type: application/octet-stream` and
   `Content-Range: bytes <start>-<end>/<total>`. The start and end are
   zero-based and inclusive; for example, the first 256 KiB of a 900000-byte
   file uses `bytes 0-262143/900000`. A successful response is `204 No Content`
   and its `Upload-Offset` header gives the next byte to send.
3. **Complete:** When the offset equals the file length, send
   `POST /files/transfers/{transferId}/complete`. The server verifies the
   checksum and persists the document. The JSON response contains `transferId`,
   `documentId`, and `sha256`. An empty file needs no PATCH requests.

If a request fails or the connection is lost, query
`GET /files/transfers/{transferId}` and resume at the returned `offset`. Use
the original file: the status response does not expose its original checksum,
but completion checks the entire uploaded content against the checksum supplied
at creation. A `409 Conflict` can indicate a conflicting range or incomplete
transfer; a checksum mismatch at completion returns `422`. A successful
completion can be retried while the session exists. Sessions eventually expire
after inactivity; deleting a session with `DELETE /files/transfers/{transferId}`
abandons it.

## Node.js example

This example uses Node.js 20 or later and its built-in `fetch`. Save it as
`upload.mjs` and run it from a trusted environment. It uses HTTP Basic
authentication, obtains the CSRF token from the login page, and keeps the
associated cookie. It prints the transfer ID as soon as the session is
created, so save that ID to resume if the process stops. A production
application should persist the ID and its association with the file.

Set `FILESERVER_USER` and `FILESERVER_PASSWORD` in the process environment.
Optionally set `FILESERVER_URL` (defaults to `http://localhost:8080`). Use
HTTPS outside a trusted local environment; Basic credentials and file contents
are otherwise sent unencrypted. Do not embed credentials in browser code.

```javascript
import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { open } from "node:fs/promises";
import { basename } from "node:path";

const [filePath, resumeId] = process.argv.slice(2);
const baseUrl = (process.env.FILESERVER_URL || "http://localhost:8080").replace(/\/+$/, "");
const { FILESERVER_USER: user, FILESERVER_PASSWORD: password } = process.env;
if (!filePath || !user || !password) {
  throw new Error("Usage: node upload.mjs <file> [transferId]; set FILESERVER_USER and FILESERVER_PASSWORD");
}

const auth = `Basic ${Buffer.from(`${user}:${password}`, "utf8").toString("base64")}`;
const login = await fetch(`${baseUrl}/login`, { redirect: "manual" });
if (!login.ok) throw new Error(`Could not fetch login page: HTTP ${login.status}`);
const loginHtml = await login.text();
const token = loginHtml.match(/name="_csrf".*?value="([^"]*)"/)?.[1];
const cookie = login.headers.getSetCookie().map(value => value.split(";", 1)[0]).join("; ");
if (!token || !cookie) throw new Error("Login response did not contain a CSRF token and session cookie");

async function request(path, { method = "GET", headers = {}, body } = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    redirect: "manual",
    headers: { Authorization: auth, Cookie: cookie, "X-CSRF-TOKEN": token, ...headers },
    body,
  });
  if (!response.ok) {
    throw new Error(`${method} ${path}: HTTP ${response.status}: ${await response.text()}`);
  }
  return response;
}

async function sha256(path) {
  const hash = createHash("sha256");
  for await (const chunk of createReadStream(path)) hash.update(chunk);
  return hash.digest("hex");
}

const file = await open(filePath, "r");
try {
  const { size } = await file.stat();
  if (!Number.isSafeInteger(size)) throw new Error("File size exceeds JavaScript's safe integer range");
  let status;
  if (resumeId) {
    status = await (await request(`/files/transfers/${encodeURIComponent(resumeId)}`)).json();
  } else {
    status = await (await request("/files/transfers", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        filename: basename(filePath),
        size,
        sha256: await sha256(filePath),
        description: "Uploaded from Node.js",
        tags: [],
      }),
    })).json();
  }

  const id = status.transferId;
  console.log(`Transfer ID: ${id}`);
  console.log(`Resume with: node upload.mjs <same-file> ${id}`);
  if (status.length !== size || !Number.isSafeInteger(status.offset) ||
      status.offset < 0 || status.offset > size) {
    throw new Error("Local file size or server offset does not match the session");
  }

  let offset = status.offset;
  while (offset < size) {
    const length = Math.min(256 * 1024, size - offset);
    const chunk = Buffer.alloc(length);
    let read = 0;
    while (read < length) {
      const { bytesRead } = await file.read(chunk, read, length - read, offset + read);
      if (bytesRead === 0) throw new Error("File ended before the expected size");
      read += bytesRead;
    }
    const end = offset + length - 1;
    const response = await request(`/files/transfers/${encodeURIComponent(id)}`, {
      method: "PATCH",
      headers: {
        "Content-Type": "application/octet-stream",
        "Content-Range": `bytes ${offset}-${end}/${size}`,
      },
      body: chunk,
    });
    const next = response.headers.get("Upload-Offset");
    if (next === null || Number(next) !== end + 1) {
      throw new Error("Server returned an unexpected upload offset");
    }
    offset = end + 1;
    console.log(`Uploaded ${offset} of ${size} bytes`);
  }

  const result = await (await request(`/files/transfers/${encodeURIComponent(id)}/complete`, {
    method: "POST",
  })).json();
  console.log("Document ID:", result.documentId);
} finally {
  await file.close();
}
```

For example, in PowerShell:

```powershell
$env:FILESERVER_USER = '<username>'
$env:FILESERVER_PASSWORD = '<password>'
node upload.mjs 'C:\path\to\file.pdf'
# After an interruption:
node upload.mjs 'C:\path\to\file.pdf' '<transferId>'
```

This example targets a server-side Node.js application, not a browser. A
browser application needs an appropriate same-origin deployment or explicit
CORS configuration, including credentials and exposed upload headers.
