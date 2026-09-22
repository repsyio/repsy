// Repsy e2e test crate (clients/cargo.ts). Static and dependency-free on purpose: this harness must
// never let `cargo publish`/`cargo fetch` reach the real crates.io for anything.
pub fn name() -> &'static str {
    "repsy-e2e"
}
