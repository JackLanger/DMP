import { useRef, useState, type FormEvent } from "react";
import { invoke } from "@tauri-apps/api/core";
import "./fileserver.css";

type FileMetadata = {
  id: string;
  name: string;
  description?: string;
  createdAt?: string;
  tags?: string[];
};

function basicAuth(username: string, password: string): string {
  const bytes = new TextEncoder().encode(`${username}:${password}`);
  const binary = Array.from(bytes, (byte) => String.fromCharCode(byte)).join("");
  return `Basic ${btoa(binary)}`;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

async function checkResponse(response: Response): Promise<Response> {
  if (!response.ok) {
    const detail = await response.text();
    throw new Error(`Request failed (${response.status})${detail ? `: ${detail}` : ""}`);
  }
  return response;
}

export function FileServerView() {
  const [serverUrl, setServerUrl] = useState("http://localhost:8080");
  const [username, setUsername] = useState("dev");
  const [password, setPassword] = useState("");
  const [files, setFiles] = useState<FileMetadata[]>([]);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const [description, setDescription] = useState("");
  const [tags, setTags] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const baseUrl = serverUrl.trim().replace(/\/+$/, "");
  const authorization = basicAuth(username, password);

  async function refreshFiles() {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const response = await fetch(`${baseUrl}/files/all`, {
        headers: { Authorization: authorization },
        credentials: "include",
      });
      const result = await checkResponse(response);
      setFiles((await result.json()) as FileMetadata[]);
    } catch (reason) {
      setError(errorMessage(reason));
    } finally {
      setBusy(false);
    }
  }

  async function uploadFile(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selectedFile) {
      setError("Choose a file to upload.");
      return;
    }

    setBusy(true);
    setError("");
    setNotice("");
    try {
      const loginResponse = await fetch(`${baseUrl}/login`, {
        headers: { Authorization: authorization },
        credentials: "include",
      });
      await checkResponse(loginResponse);
      const loginHtml = await loginResponse.text();
      const csrfToken = loginHtml.match(/name="_csrf"[^>]*value="([^"]+)"/)?.[1];
      if (!csrfToken) {
        throw new Error("Could not find a CSRF token in the FileServer login response.");
      }

      const form = new FormData();
      form.append("file", selectedFile, selectedFile.name);
      form.append(
        "metadata",
        new Blob(
          [JSON.stringify({
            description,
            tags: tags.split(",").map((tag) => tag.trim()).filter(Boolean),
          })],
          { type: "application/json" },
        ),
      );

      const response = await fetch(`${baseUrl}/files`, {
        method: "POST",
        headers: {
          Authorization: authorization,
          "X-CSRF-TOKEN": csrfToken,
        },
        credentials: "include",
        body: form,
      });
      await checkResponse(response);
      setSelectedFile(null);
      if (fileInput.current) fileInput.current.value = "";
      setDescription("");
      setTags("");
      await refreshFiles();
      setNotice(`${selectedFile.name} uploaded successfully.`);
    } catch (reason) {
      setError(errorMessage(reason));
    } finally {
      setBusy(false);
    }
  }

  async function downloadFile(file: FileMetadata) {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const savedPath = await invoke<string>("download_file", {
        serverUrl: baseUrl,
        username,
        password,
        id: file.id,
        filename: file.name,
      });
      setNotice(`Saved to ${savedPath}`);
    } catch (reason) {
      setError(errorMessage(reason));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="file-manager">
      <header className="file-manager__header">
        <div>
          <p className="file-manager__eyebrow">DMP · STORAGE</p>
          <h1>File server</h1>
          <p className="file-manager__subtitle">Upload, browse, and download your files.</p>
        </div>
        <span className="file-manager__count">{files.length} files</span>
      </header>

      <section className="file-manager__card file-manager__connection" aria-label="FileServer connection">
        <label>
          Server URL
          <input value={serverUrl} onChange={(event) => setServerUrl(event.currentTarget.value)} />
        </label>
        <label>
          Username
          <input autoComplete="username" value={username} onChange={(event) => setUsername(event.currentTarget.value)} />
        </label>
        <label>
          Password
          <input
            autoComplete="current-password"
            type="password"
            value={password}
            onChange={(event) => setPassword(event.currentTarget.value)}
          />
        </label>
        <button className="button button--secondary" disabled={busy || !baseUrl} onClick={() => void refreshFiles()}>
          {busy ? "Working…" : "Connect / refresh"}
        </button>
      </section>

      <section className="file-manager__card" aria-labelledby="upload-heading">
        <div className="section-heading">
          <div>
            <p className="file-manager__eyebrow">ADD TO STORAGE</p>
            <h2 id="upload-heading">Upload a file</h2>
          </div>
        </div>
        <form className="upload-form" onSubmit={(event) => void uploadFile(event)}>
          <label className="file-picker">
            <span>{selectedFile ? selectedFile.name : "Choose a file…"}</span>
            <input ref={fileInput} type="file" onChange={(event) => setSelectedFile(event.currentTarget.files?.[0] ?? null)} />
          </label>
          <label>
            Description <span className="optional">Optional</span>
            <input value={description} onChange={(event) => setDescription(event.currentTarget.value)} placeholder="What is this file?" />
          </label>
          <label>
            Tags <span className="optional">Comma separated</span>
            <input value={tags} onChange={(event) => setTags(event.currentTarget.value)} placeholder="research, draft" />
          </label>
          <button className="button button--primary" type="submit" disabled={busy || !selectedFile}>
            Upload file
          </button>
        </form>
      </section>

      <section className="file-manager__card" aria-labelledby="files-heading">
        <div className="section-heading">
          <div>
            <p className="file-manager__eyebrow">YOUR STORAGE</p>
            <h2 id="files-heading">Uploaded files</h2>
          </div>
          <button className="button button--text" disabled={busy} onClick={() => void refreshFiles()}>
            Refresh list
          </button>
        </div>
        {error && <p className="feedback feedback--error" role="alert">{error}</p>}
        {notice && <p className="feedback feedback--success" role="status">{notice}</p>}
        {files.length === 0 ? (
          <div className="empty-state">
            <span className="empty-state__icon" aria-hidden="true">↥</span>
            <strong>No files to show yet</strong>
            <span>Connect to the FileServer or upload your first file.</span>
          </div>
        ) : (
          <ul className="file-list">
            {files.map((file) => (
              <li className="file-row" key={file.id}>
                <div className="file-row__icon" aria-hidden="true">FILE</div>
                <div className="file-row__details">
                  <strong title={file.name}>{file.name}</strong>
                  <span>
                    {file.createdAt ? new Date(file.createdAt).toLocaleString() : "Uploaded"}
                    {file.tags?.length ? ` · ${file.tags.join(", ")}` : ""}
                  </span>
                </div>
                <button className="button button--secondary" disabled={busy} onClick={() => void downloadFile(file)}>
                  Download
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>
      <p className="file-manager__security-note">Credentials are used for this session only. Use HTTPS when connecting over an untrusted network.</p>
    </main>
  );
}
