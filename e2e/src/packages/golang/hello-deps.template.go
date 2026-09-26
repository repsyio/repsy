// Package e2e is the RPS-1479 fixture: a module that depends on other modules.
package e2e

import (
{{#requires}}
  {{{alias}}} "{{{modulePath}}}"
{{/requires}}
)

// Marker identifies this publish.
const Marker = "{{{marker}}}"

// DepMarkers reports the Marker of every dependency this module imports, in the order of its go.mod.
func DepMarkers() []string {
  return []string{
{{#requires}}
    {{{alias}}}.Marker,
{{/requires}}
  }
}
