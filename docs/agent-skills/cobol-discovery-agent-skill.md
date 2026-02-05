# CobolDiscoveryAgent Skill Definition

## Core Competencies
- Directory traversal and file system navigation
- Pattern matching for file types (.cbl, .cpy, .jcl)
- Metadata extraction (size, timestamps, encoding detection)
- JSON serialization of file inventories

## Knowledge Domains
- COBOL file naming conventions
- Mainframe file system structures
- Character encoding (EBCDIC vs ASCII)
- Copybook vs program distinction

## Interaction Protocols
- Input: Source directory path, include/exclude patterns
- Output: FileInventory JSON
- Consumers: CobolAnalyzerAgent, DependencyMapperAgent
- Error handling: Permission errors, missing directories

## Performance Requirements
- Process 1000+ files in < 5 minutes
- Memory efficient streaming for large codebases
- Parallel processing where possible
