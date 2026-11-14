use wasm_bindgen::prelude::*;

#[wasm_bindgen]
pub fn greet(name: &str) -> String {
    format!("Hello, {name}! You've been greeted from Rust!")
}

#[cfg(test)]
mod tests {
    use super::greet;

    #[test]
    fn greeting_preserves_unicode() {
        assert_eq!(greet("Zoë 世界"), "Hello, Zoë 世界! You've been greeted from Rust!");
    }
}
