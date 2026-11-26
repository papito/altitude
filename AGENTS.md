# Altitude – agent entry point

This file only routes. Each guide below opens with a binding **Do** section. Guidance goes in the guide that owns the area, never here; a new guide gets a row.

| Working on | Read before starting |
|---|---|
| Anything | [altitude/AGENTS.md](altitude/AGENTS.md): rules for every change, architecture, the schema rule, build and test commands |
| Frontend: Twirl templates, `altitude/static/js/`, CSS, design | also [altitude/views/AGENTS.md](altitude/views/AGENTS.md): frontend rules, template, event and component conventions, the style guide |
| Face detection and recognition | also [docs/faces.md](docs/faces.md) |
| Image edits: the Edit, its geometry and per-pixel math, the two renderers and their parity | also [docs/image-edits.md](docs/image-edits.md) |
| Security, data safety, operational readiness | also [docs/READINESS.md](docs/READINESS.md): what live use needs and where the code stands |
| Naming a domain concept in code, UI text or docs | [CONTEXT.md](CONTEXT.md): the glossary |

Reference, when relevant: [docs/test-coverage.md](docs/test-coverage.md) (service-layer coverage and gaps), [docs/testing-cookbook.md](docs/testing-cookbook.md) (recipes for test checks and fixtures), [docs/curation-batch-detection.md](docs/curation-batch-detection.md) (Curation backend, UX, and research notes on batch detection, quality metrics and face grading), [altitude/static/js/lib/README.md](altitude/static/js/lib/README.md) (vendored libraries, versions, local patches), [docs/reference/alpine-components/](docs/reference/alpine-components/README.md) (Alpine UI component notes).
