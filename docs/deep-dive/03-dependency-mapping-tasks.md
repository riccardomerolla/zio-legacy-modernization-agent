# Dependency Mapping Phase - Task Breakdown

## Overview
Map relationships between COBOL programs and copybooks.

## Tasks

### TASK-03-001: Extract COPY Statements
- **Agent**: DependencyMapperAgent
- **Dependencies**: Analysis complete
- **Inputs**: CobolAnalysis results
- **Outputs**: COPY statement list
- **Complexity**: Low

### TASK-03-002: Extract Program Calls
- **Agent**: DependencyMapperAgent
- **Dependencies**: Analysis complete
- **Inputs**: CobolAnalysis results
- **Outputs**: CALL statement list
- **Complexity**: Medium

### TASK-03-003: Build Dependency Graph
- **Agent**: DependencyMapperAgent
- **Dependencies**: TASK-03-001, TASK-03-002
- **Inputs**: COPY and CALL statements
- **Outputs**: Directed graph structure
- **Complexity**: High

### TASK-03-004: Calculate Complexity Metrics
- **Agent**: DependencyMapperAgent
- **Dependencies**: TASK-03-003
- **Inputs**: Dependency graph
- **Outputs**: Complexity scores per node
- **Complexity**: Medium

### TASK-03-005: Generate Mermaid Diagram
- **Agent**: DependencyMapperAgent
- **Dependencies**: TASK-03-004
- **Inputs**: Dependency graph
- **Outputs**: dependency-diagram.md
- **Complexity**: Low

### TASK-03-006: Identify Service Boundaries
- **Agent**: DependencyMapperAgent
- **Dependencies**: TASK-03-004
- **Inputs**: Dependency graph with metrics
- **Outputs**: service-candidates.json
- **Complexity**: High

## Success Criteria
- Complete dependency graph
- No orphaned nodes
- Service boundaries identified
