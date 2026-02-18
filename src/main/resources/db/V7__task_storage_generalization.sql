DROP TABLE IF EXISTS phase_progress;
DROP TABLE IF EXISTS dependencies;
DROP TABLE IF EXISTS cobol_analyses;
DROP TABLE IF EXISTS cobol_files;

CREATE TABLE IF NOT EXISTS task_reports (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  task_run_id INTEGER NOT NULL,
  step_name TEXT NOT NULL,
  report_type TEXT NOT NULL CHECK(report_type IN ('markdown', 'graph')),
  content TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  FOREIGN KEY (task_run_id) REFERENCES task_runs(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS task_artifacts (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  task_run_id INTEGER NOT NULL,
  step_name TEXT NOT NULL,
  key TEXT NOT NULL,
  value TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  FOREIGN KEY (task_run_id) REFERENCES task_runs(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_task_reports_task_run_id ON task_reports(task_run_id);
CREATE INDEX IF NOT EXISTS idx_task_artifacts_task_run_id ON task_artifacts(task_run_id);
