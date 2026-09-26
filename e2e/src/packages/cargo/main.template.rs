// Repsy e2e binary crate (clients/cargo.ts, RPS-1486): `cargo install` builds and links this, then the
// spec runs the binary and reads the marker back. Dependency-free unless a Repsy-registry dependency
// is declared (then the line below prints its version too).
fn main() {
    println!("marker={{{marker}}}");
{{#dep}}
    println!("dep={}", {{{dep}}}::version());
{{/dep}}
}
