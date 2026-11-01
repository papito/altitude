# Altitude – agent entry point

This file only routes. The rules and the architecture live in the guides below, and each guide opens with a **Do** section that is binding. Guidance goes in the guide that owns the area, never here; a new guide gets a row.

| Working on | Read before starting |
|---|---|
| Anything | [altitude/AGENTS.md](altitude/AGENTS.md): the rules for every change, architecture, the schema rule, build and test commands |
| Frontend: Twirl templates, `altitude/static/js/`, CSS, design | also [altitude/views/AGENTS.md](altitude/views/AGENTS.md): frontend rules, template, event and component conventions, the style guide |
| Face detection and recognition | also [docs/faces.md](docs/faces.md) |
| Security, data safety, operational readiness | also [docs/READINESS.md](docs/READINESS.md): what live use needs and where the code stands on each item |
| Naming a domain concept in code, UI text or docs | [CONTEXT.md](CONTEXT.md): the glossary |

Reference, when relevant: [docs/test-coverage.md](docs/test-coverage.md) (service-layer coverage and gaps), [docs/curation-batch-detection.md](docs/curation-batch-detection.md) (research notes: automatic batch detection, image quality metrics, face grading for a Curate view), [altitude/static/js/lib/README.md](altitude/static/js/lib/README.md) (vendored libraries, versions, local patches), [docs/reference/alpine-components/](docs/reference/alpine-components/README.md) (notes on Alpine UI components).
