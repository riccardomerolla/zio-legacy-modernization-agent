# Migration Reports

This directory contains detailed reports and artifacts generated during the migration process.

## Report Types

### Discovery Phase
- `inventory.json` - Complete file catalog
- `discovery-report.md` - Human-readable summary

### Analysis Phase
- `analysis/<filename>.json` - Per-file structural analysis
- `analysis-summary.json` - Aggregated statistics

### Dependency Mapping Phase
- `dependency-map.json` - Graph representation
- `dependency-diagram.md` - Mermaid visualization
- `service-candidates.json` - Recommended microservice boundaries

### Transformation Phase
- `transformation-report.json` - Transformation metrics
- Per-service transformation details

### Validation Phase
- `tests/<package>/` - Generated test suites
- `validation-report.json` - Test results and coverage
- `coverage/` - JaCoCo coverage reports

### Documentation Phase
- `technical-design.md` - Architecture documentation
- `api-reference.md` - API documentation
- `migration-summary.md` - Executive summary
- `deployment-guide.md` - Deployment instructions

## Viewing Reports

Most reports are in JSON or Markdown format and can be viewed in any text editor or IDE.

For Mermaid diagrams, use a Mermaid-compatible viewer:
- GitHub/GitLab (renders automatically)
- VS Code (with Mermaid extension)
- Online: https://mermaid.live/
