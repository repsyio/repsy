// Repsy e2e test package (clients/npm.ts). Static and dependency-free on purpose: this harness must
// never let `npm install` reach the real npmjs.org for anything.
module.exports = { name: 'repsy-e2e' };
