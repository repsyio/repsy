// Package main is the RPS-294 real-client consumer: `go get`/`go build`, then run it to prove the
// resolved module's own code (not just its bytes) is reachable and correct.
package main

import (
  "fmt"

  e2e "{{{modulePath}}}"
)

func main() {
  fmt.Println(e2e.Marker)
}
