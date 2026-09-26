// Repsy e2e library crate (clients/cargo.ts, RPS-1486): reports the version it was published as, so a
// consumer that resolved it can print which one it got.
pub fn version() -> &'static str {
    "{{{version}}}"
}
