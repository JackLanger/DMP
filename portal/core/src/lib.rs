#[cfg(target_arch = "wasm32")]
use wasm_bindgen::prelude::*;

pub mod config {
    #[cfg(target_arch = "wasm32")]
    use serde::Serialize;
    use std::fs::File;
    use std::io::Read;
    use std::path::Path;

    #[cfg_attr(target_arch = "wasm32", derive(Serialize))]
    pub struct AppConfig {
        pub fileserver_url: String,
        pub(crate) fileserver_user: String,
        pub fileserver_password: String,
    }

    impl AppConfig {
        pub fn new() -> AppConfig {
            AppConfig {
                fileserver_user: "".to_string(),
                fileserver_url: "".to_string(),
                fileserver_password: "".to_string(),
            }
        }

        pub fn load() -> AppConfig {
            AppConfig::load_from(Path::new(".configuration"))
        }

        fn load_from(path: &Path) -> AppConfig {
            // todo error handling
            match File::open(path) {
                Ok(f) => AppConfig::from_file(f),
                Err(_) => AppConfig::new(),
            }
        }

        fn from_file(config_file: std::fs::File) -> AppConfig {
            let mut f = config_file;
            let mut buf: Vec<u8> = Vec::new();
            let _ = f.read_to_end(&mut buf);
            let mut config = AppConfig::new();

            for line in String::from_utf8(buf).unwrap_or(String::new()).split('\n') {
                let kv: Vec<&str> = line.split('=').collect();
                let key = kv[0];
                let mut value = "";
                if kv.len() > 1 {
                    value = kv[1];
                }

                match key.trim() {
                    "fileserver_user" => config.fileserver_user = value.trim().to_string(),
                    "fileserver_url" => config.fileserver_url = value.trim().to_string(),
                    "fileserver_password" => config.fileserver_password = value.trim().to_string(),
                    _ => {}
                }
            }
            config
        }

        pub fn save(self) {
            self.save_to(Path::new(".configuration"));
        }

        fn save_to(self, path: &Path) {
            use std::io::Write;
            let mut config_file = File::create(path).unwrap();
            let template = self.serialize().into_bytes();
            let _ = config_file.write(&template);
        }

        fn serialize(&self) -> String {
            format!(
                "fileserver_user={}\nfileserver_url={}\nfileserver_password={}",
                self.fileserver_user, self.fileserver_url, self.fileserver_password
            )
        }
    }

    #[cfg(test)]
    mod tests {
        use super::AppConfig;

        #[test]
        fn write_and_retrieve_config() {
            let directory = std::env::temp_dir().join(format!(
                "dmp-config-test-{}-{}",
                std::process::id(),
                std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()
            ));
            std::fs::create_dir(&directory).unwrap();
            let path = directory.join("configuration");
            let config = AppConfig {
                fileserver_user: "user".to_string(),
                fileserver_url: "url".to_string(),
                fileserver_password: "123".to_string(),
            };

            let actual = config.serialize();
            config.save_to(&path);

            let from_file = AppConfig::load_from(&path);

            assert_eq!(actual, from_file.serialize());
            std::fs::remove_file(path).unwrap();
            std::fs::remove_dir(directory).unwrap();
        }

        #[test]
        fn serialize_config() {
            let config = AppConfig {
                fileserver_user: "user".to_string(),
                fileserver_url: "url".to_string(),
                fileserver_password: "123".to_string(),
            };

            let expected = "fileserver_user=user\nfileserver_url=url\nfileserver_password=123";
            assert_eq!(config.serialize(), expected);
        }
    }
}

#[cfg(target_arch = "wasm32")]
#[wasm_bindgen]
pub fn default_config() -> Result<JsValue, JsValue> {
    serde_wasm_bindgen::to_value(&config::AppConfig::new())
        .map_err(|error| JsValue::from_str(&error.to_string()))
}

use std::path::{Path, PathBuf};
#[cfg_attr(target_arch = "wasm32", wasm_bindgen)]
pub async fn download_file(
    download_dir: Option<String>,
    id: String,
    filename: String,
    access_token: String,
) -> Result<String, String> {
    if id.is_empty()
        || !id
            .chars()
            .all(|character| character.is_ascii_hexdigit() || character == '-')
    {
        return Err("The FileServer returned an invalid file ID.".to_string());
    }

    let config = config::AppConfig::load();
    let username = config.fileserver_user;
    let server_url = config.fileserver_url;

    let url = format!("{}/files/{}/download", server_url.trim_end_matches('/'), id);
    let response = reqwest::Client::new()
        .get(url)
        .header("X-User", username)
        .bearer_auth(access_token)
        .send()
        .await
        .map_err(|error| format!("Could not reach the FileServer: {error}"))?
        .error_for_status()
        .map_err(|error| format!("FileServer download failed: {error}"))?;
    let bytes = response
        .bytes()
        .await
        .map_err(|error| format!("Could not read the downloaded file: {error}"))?;

    let download_dir = download_dir.map(PathBuf::from)
        .filter(|directory| directory.is_dir())
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

fn upload_file(
    file_path: String,
    file_name: String,
    file_description: String,
    file_tags: Vec<String>,
){

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
