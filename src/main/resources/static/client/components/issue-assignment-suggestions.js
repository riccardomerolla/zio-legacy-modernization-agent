function parseCapabilities(raw) {
  return String(raw || '')
    .split(',')
    .map((value) => value.trim().toLowerCase())
    .filter((value) => value.length > 0);
}

function renderSuggestions(container, suggestions, select) {
  if (!container) return;

  if (!Array.isArray(suggestions) || suggestions.length === 0) {
    container.innerHTML = '<p class="text-xs text-slate-400">No capability-based suggestions available.</p>';
    return;
  }

  const chips = suggestions
    .slice(0, 5)
    .map((item) => {
      const scorePercent = Math.round((Number(item.score || 0) || 0) * 100);
      const activeRuns = Number(item.activeRuns || 0);
      return `
        <button
          type="button"
          class="rounded-full border border-indigo-400/30 bg-indigo-500/20 px-2 py-1 text-xs text-indigo-100 hover:bg-indigo-500/30"
          data-suggestion-agent="${String(item.agentName || '')}"
          title="overlap ${item.overlapCount || 0}/${item.requiredCount || 0}, active runs ${activeRuns}">
          ${String(item.agentName || 'unknown')} (${scorePercent}%)
        </button>
      `;
    })
    .join('');

  container.innerHTML = `
    <p class="mb-1 text-xs text-slate-400">Suggested agents by capability match:</p>
    <div class="flex flex-wrap gap-1.5">${chips}</div>
  `;

  container.querySelectorAll('[data-suggestion-agent]').forEach((button) => {
    button.addEventListener('click', () => {
      if (select) {
        select.value = button.dataset.suggestionAgent || '';
        select.dispatchEvent(new Event('change', { bubbles: true }));
      }
    });
  });
}

async function loadSuggestions(form, container, select) {
  const required = parseCapabilities(container?.dataset?.requiredCapabilities || '');
  const query = encodeURIComponent(required.join(','));
  const url = `/api/agents/match?capabilities=${query}`;

  try {
    const response = await fetch(url, { headers: { Accept: 'application/json' } });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const payload = await response.json();
    renderSuggestions(container, payload, select);
  } catch (_) {
    container.innerHTML = '<p class="text-xs text-slate-400">Could not load suggestions.</p>';
  }
}

async function autoAssign(form, button, issueId) {
  const workspaceField = form.querySelector('[name="workspaceId"]');
  const workspaceId = workspaceField ? String(workspaceField.value || '').trim() : '';

  button.disabled = true;
  const original = button.textContent;
  button.textContent = 'Auto-Assigning...';

  try {
    const response = await fetch(`/api/issues/${encodeURIComponent(issueId)}/auto-assign`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ workspaceId: workspaceId || null }),
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const payload = await response.json();
    if (payload && payload.assigned) {
      window.location.reload();
      return;
    }

    const reason = (payload && payload.reason) ? String(payload.reason) : 'No available match';
    window.alert(reason);
  } catch (_) {
    window.alert('Auto-assignment failed.');
  } finally {
    button.disabled = false;
    button.textContent = original;
  }
}

function initAssignmentSuggestions() {
  const form = document.querySelector('[data-assignment-form="true"]');
  if (!form) return;

  const issueId = form.dataset.issueId;
  const container = form.parentElement?.querySelector('[data-assignment-suggestions="true"]');
  const agentSelect = form.querySelector('[name="agentName"]');
  if (container) {
    loadSuggestions(form, container, agentSelect);
  }

  const autoAssignButton = form.querySelector('[data-auto-assign-btn="true"]');
  if (autoAssignButton && issueId) {
    autoAssignButton.addEventListener('click', () => autoAssign(form, autoAssignButton, issueId));
  }
}

initAssignmentSuggestions();
