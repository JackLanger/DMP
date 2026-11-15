use std::path::{Path, PathBuf};

use tauri::{AppHandle, Manager};

#[tauri::command(rename_all = "camelCase")]
async fn download_file(
    app: AppHandle,
    server_url: String,
    username: String,
    password: String,
    id: String,
    filename: String,
) -> Result<String, String> {
    if id.is_empty()
        || !id
            .chars()
            .all(|character| character.is_ascii_hexdigit() || character == '-')
    {
        return Err("The FileServer returned an invalid file ID.".to_string());
    }

    let url = format!("{}/files/{}/download", server_url.trim_end_matches('/'), id);
    let response = reqwest::Client::new()
        .get(url)
        .basic_auth(username, Some(password))
        .send()
        .await
        .map_err(|error| format!("Could not reach the FileServer: {error}"))?
        .error_for_status()
        .map_err(|error| format!("FileServer download failed: {error}"))?;
    let bytes = response
        .bytes()
        .await
        .map_err(|error| format!("Could not read the downloaded file: {error}"))?;

    let download_dir = app
        .path()
        .download_dir()
        .ok()
        .filter(|directory| directory.is_dir())
        .or_else(|| {
            app.path()
                .home_dir()
                .ok()
                .filter(|directory| directory.is_dir())
        })
        .ok_or_else(|| "Could not locate a Downloads or home directory.".to_string())?;

    let safe_filename = Path::new(&filename)
        .file_name()
        .and_then(|name| name.to_str())
        .filter(|name| !name.is_empty() && *name != "." && *name != "..")
        .unwrap_or("download");
    let destination = unique_download_path(&download_dir, safe_filename);
    std::fs::write(&destination, bytes)
        .map_err(|error| format!("Could not save the file: {error}"))?;

    Ok(destination.display().to_string())
}

fn unique_download_path(directory: &Path, filename: &str) -> PathBuf {
    let original = directory.join(filename);
    if !original.exists() {
        return original;
    }

    let path = Path::new(filename);
    let stem = path
        .file_stem()
        .and_then(|value| value.to_str())
        .unwrap_or("download");
    let extension = path.extension().and_then(|value| value.to_str());
    for index in 1_u32.. {
        let name = match extension {
            Some(extension) => format!("{stem} ({index}).{extension}"),
            None => format!("{stem} ({index})"),
        };
        let candidate = directory.join(name);
        if !candidate.exists() {
            return candidate;
        }
    }

    unreachable!("u32 filename suffix space exhausted")
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![download_file])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
