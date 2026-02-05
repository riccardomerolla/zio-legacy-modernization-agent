# Analysis Phase - Task Breakdown

## Overview
Deep structural analysis of COBOL programs using Gemini CLI.

## Tasks

### TASK-02-001: Parse IDENTIFICATION Division
- **Agent**: CobolAnalyzerAgent
- **Dependencies**: Discovery complete
- **Inputs**: COBOL source file
- **Outputs**: Program metadata
- **Complexity**: Low

### TASK-02-002: Parse DATA Division
- **Agent**: CobolAnalyzerAgent
- **Dependencies**: TASK-02-001
- **Inputs**: COBOL source file
- **Outputs**: Variable definitions, data structures
- **Complexity**: High

### TASK-02-003: Parse PROCEDURE Division
- **Agent**: CobolAnalyzerAgent
- **Dependencies**: TASK-02-002
- **Inputs**: COBOL source file
- **Outputs**: Control flow, statements, procedures
- **Complexity**: High

### TASK-02-004: Detect Copybook Dependencies
- **Agent**: CobolAnalyzerAgent
- **Dependencies**: TASK-02-003
- **Inputs**: COBOL source with COPY statements
- **Outputs**: List of copybook references
- **Complexity**: Medium

### TASK-02-005: Invoke Gemini CLI for Analysis
- **Agent**: CobolAnalyzerAgent
- **Dependencies**: TASK-02-001 to TASK-02-004
- **Inputs**: COBOL source + analysis prompt
- **Outputs**: Structured JSON analysis
- **Complexity**: Medium

### TASK-02-006: Validate Analysis Output
- **Agent**: CobolAnalyzerAgent
- **Dependencies**: TASK-02-005
- **Inputs**: JSON response from Gemini
- **Outputs**: Validated CobolAnalysis object
- **Complexity**: Medium

## Success Criteria
- All files analyzed successfully
- Structured data validated against schema
- No AI invocation failures
