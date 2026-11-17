use wasm_bindgen::prelude::*;

use futures_util::StreamExt;
use serde::Deserialize;

#[derive(Debug, Deserialize)]
struct Event {
    message: String,
}

#[wasm_bindgen]
pub fn greet(name: &str) -> String {
    String::from(format!("Hello, {name}! You've been greeted from Rust!"))
}

#[cfg(test)]
mod tests {
    use super::greet;

    #[test]
    fn greeting_preserves_unicode() {
        assert_eq!(
            greet("Zoë 世界"),
            "Hello, Zoë 世界! You've been greeted from Rust!"
        );
    }
}
