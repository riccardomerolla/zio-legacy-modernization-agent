function parseCsv(raw) {
  return String(raw || '')
    .split(',')
    .map((v) => v.trim())
    .filter((v) => v.length > 0);
}

function selectedIds(scope) {
  const selector = `input[data-bulk-select="${scope}"]:checked`;
  return Array.from(document.querySelectorAll(selector))
    .map((el) => el.dataset.issueId)
    .filter((v) => v && v.length > 0);
}

function setToolbar(scope) {
  const ids = selectedIds(scope);
  const toolbar = document.querySelector(`[data-bulk-toolbar="${scope}"]`);
  if (!toolbar) return ids;

  toolbar.classList.toggle('hidden', ids.length === 0);
  toolbar.dataset.bulkSelectedCount = String(ids.length);
  const label = toolbar.querySelector(`[data-bulk-count-label="${scope}"]`);
  if (label) label.textContent = String(ids.length);
  return ids;
}

async function callJson(url, method, body) {
  const response = await fetch(url, {
    method,
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(text || `${method} ${url} failed`);
  }
  return text ? JSON.parse(text) : {};
}

function progressNode(scope) {
  return document.querySelector(`[data-bulk-progress="${scope}"]`);
}

function previewNode(scope) {
  return document.querySelector(`[data-import-preview="${scope}"]`);
}

function setProgress(scope, text, isError = false) {
  const node = progressNode(scope);
  if (!node) return;
  node.textContent = text || '';
  node.className = isError
    ? 'mt-2 text-xs text-red-300'
    : 'mt-2 text-xs text-indigo-100/80';
}

async function runBulkAction(scope, action) {
  const ids = selectedIds(scope);
  if (ids.length === 0) {
    setProgress(scope, 'Select at least one issue.', true);
    return;
  }

  try {
    setProgress(scope, `Processing ${ids.length} issue(s)...`);
    let result;
    if (action === 'assign') {
      const workspaceId = document.querySelector(`[data-bulk-workspace="${scope}"]`)?.value?.trim() || '';
      const agentId = document.querySelector(`[data-bulk-agent="${scope}"]`)?.value?.trim() || '';
      if (!workspaceId || !agentId) {
        setProgress(scope, 'Workspace and agent are required for bulk assign.', true);
        return;
      }
      result = await callJson('/api/issues/bulk/assign', 'POST', { issueIds: ids, workspaceId, agentId });
    } else if (action === 'status') {
      const status = document.querySelector(`[data-bulk-status="${scope}"]`)?.value || 'Open';
      result = await callJson('/api/issues/bulk/status', 'POST', { issueIds: ids, status });
    } else if (action === 'tags') {
      const addTags = parseCsv(document.querySelector(`[data-bulk-add-tags="${scope}"]`)?.value || '');
      const removeTags = parseCsv(document.querySelector(`[data-bulk-remove-tags="${scope}"]`)?.value || '');
      result = await callJson('/api/issues/bulk/tags', 'POST', { issueIds: ids, addTags, removeTags });
    } else if (action === 'delete') {
      if (!window.confirm(`Delete ${ids.length} selected issue(s)?`)) {
        return;
      }
      result = await callJson('/api/issues/bulk', 'DELETE', { issueIds: ids });
    } else {
      return;
    }

    setProgress(scope, `Done: ${result.succeeded}/${result.requested} succeeded, ${result.failed} failed.`);
    window.location.reload();
  } catch (err) {
    setProgress(scope, `Bulk action failed: ${String(err)}`, true);
  }
}

async function runImportAction(scope, action) {
  try {
    const output = previewNode(scope);
    if (!output) return;

    if (action === 'folder-preview') {
      setProgress(scope, 'Loading folder preview...');
      const result = await callJson('/api/issues/import/folder/preview', 'GET');
      output.textContent = JSON.stringify(result, null, 2);
      setProgress(scope, `Preview loaded: ${result.length} item(s).`);
      return;
    }

    if (action === 'folder-import') {
      setProgress(scope, 'Importing folder issues...');
      const result = await callJson('/api/issues/import/folder', 'POST');
      output.textContent = JSON.stringify(result, null, 2);
      setProgress(scope, `Folder import completed: ${result.succeeded}/${result.requested}.`);
      window.location.reload();
      return;
    }

    const repo = document.querySelector(`[data-import-repo="${scope}"]`)?.value?.trim() || '';
    if (!repo) {
      setProgress(scope, 'GitHub repo is required (owner/repo).', true);
      return;
    }

    if (action === 'github-preview') {
      setProgress(scope, 'Loading GitHub preview...');
      const result = await callJson('/api/issues/import/github/preview', 'POST', { repo, state: 'open', limit: 50 });
      output.textContent = JSON.stringify(result, null, 2);
      setProgress(scope, `GitHub preview loaded: ${result.length} issue(s).`);
      return;
    }

    if (action === 'github-import') {
      setProgress(scope, 'Importing GitHub issues...');
      const result = await callJson('/api/issues/import/github', 'POST', { repo, state: 'open', limit: 50 });
      output.textContent = JSON.stringify(result, null, 2);
      setProgress(scope, `GitHub import completed: ${result.succeeded}/${result.requested}.`);
      window.location.reload();
    }
  } catch (err) {
    setProgress(scope, `Import failed: ${String(err)}`, true);
  }
}

function bindScope(scope) {
  const selectAll = document.querySelector(`[data-bulk-select-all="${scope}"]`);
  if (selectAll) {
    selectAll.addEventListener('change', () => {
      const checked = Boolean(selectAll.checked);
      document.querySelectorAll(`input[data-bulk-select="${scope}"]`).forEach((el) => {
        el.checked = checked;
      });
      setToolbar(scope);
    });
  }

  document.querySelectorAll(`input[data-bulk-select="${scope}"]`).forEach((el) => {
    el.addEventListener('change', () => {
      const all = Array.from(document.querySelectorAll(`input[data-bulk-select="${scope}"]`));
      if (selectAll && all.length > 0) {
        selectAll.checked = all.every((box) => box.checked);
      }
      setToolbar(scope);
    });

    el.addEventListener('click', (event) => {
      event.stopPropagation();
    });
  });

  document.querySelectorAll(`button[data-bulk-action][data-bulk-scope="${scope}"]`).forEach((button) => {
    button.addEventListener('click', () => runBulkAction(scope, button.dataset.bulkAction));
  });

  document.querySelectorAll(`button[data-import-action][data-bulk-scope="${scope}"]`).forEach((button) => {
    button.addEventListener('click', () => runImportAction(scope, button.dataset.importAction));
  });

  setToolbar(scope);
}

bindScope('list');
bindScope('board');
