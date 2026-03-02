const managerRoot = document.getElementById('issue-template-manager');
const listRoot = document.getElementById('issue-template-manager-list');
const feedback = document.getElementById('issue-template-manager-feedback');
const dataNode = document.getElementById('issue-template-manager-data');
const form = document.getElementById('issue-template-manager-form');
const resetButton = document.getElementById('issue-template-reset');

if (managerRoot && listRoot && dataNode && form) {
  const apiBase = managerRoot.dataset.apiBase || '/api/issue-templates';

  const fields = {
    id: document.getElementById('issue-template-id'),
    isEdit: document.getElementById('issue-template-is-edit'),
    name: document.getElementById('issue-template-name'),
    description: document.getElementById('issue-template-description'),
    issueType: document.getElementById('issue-template-issue-type'),
    priority: document.getElementById('issue-template-priority'),
    tags: document.getElementById('issue-template-tags'),
    titleTemplate: document.getElementById('issue-template-title-template'),
    descriptionTemplate: document.getElementById('issue-template-description-template'),
    variables: document.getElementById('issue-template-variables')
  };

  let templates = [];

  function setFeedback(message, isError) {
    if (!feedback) return;
    feedback.textContent = message || '';
    feedback.className = isError ? 'mt-3 text-sm text-red-300' : 'mt-3 text-sm text-emerald-300';
  }

  function parseInitialTemplates() {
    try {
      return JSON.parse(dataNode.textContent || '[]');
    } catch (_) {
      return [];
    }
  }

  function renderList() {
    const custom = templates.filter((t) => !t.isBuiltin);
    if (custom.length === 0) {
      listRoot.innerHTML = '<p class="text-sm text-slate-400">No custom templates yet.</p>';
      return;
    }

    listRoot.innerHTML = '';
    custom
      .sort((a, b) => a.name.localeCompare(b.name))
      .forEach((t) => {
        const item = document.createElement('div');
        item.className = 'rounded border border-white/10 bg-slate-800/70 p-3';

        const title = document.createElement('p');
        title.className = 'text-sm font-medium text-slate-100';
        title.textContent = `${t.name} (${t.id})`;

        const desc = document.createElement('p');
        desc.className = 'mt-1 text-xs text-slate-400';
        desc.textContent = t.description;

        const actions = document.createElement('div');
        actions.className = 'mt-2 flex items-center gap-2';

        const edit = document.createElement('button');
        edit.type = 'button';
        edit.className = 'rounded border border-indigo-300/30 px-2 py-1 text-xs font-semibold text-indigo-200 hover:bg-indigo-500/20';
        edit.textContent = 'Edit';
        edit.addEventListener('click', () => populateForm(t));

        const del = document.createElement('button');
        del.type = 'button';
        del.className = 'rounded border border-red-300/30 px-2 py-1 text-xs font-semibold text-red-200 hover:bg-red-500/20';
        del.textContent = 'Delete';
        del.addEventListener('click', async () => {
          if (!window.confirm(`Delete template ${t.name}?`)) return;
          try {
            const response = await fetch(`${apiBase}/${encodeURIComponent(t.id)}`, { method: 'DELETE' });
            if (!response.ok) throw new Error(await response.text());
            await refreshTemplates();
            resetForm();
            setFeedback(`Deleted ${t.name}.`, false);
          } catch (err) {
            setFeedback(`Delete failed: ${String(err)}`, true);
          }
        });

        actions.appendChild(edit);
        actions.appendChild(del);

        item.appendChild(title);
        item.appendChild(desc);
        item.appendChild(actions);
        listRoot.appendChild(item);
      });
  }

  function populateForm(template) {
    fields.id.value = template.id || '';
    fields.isEdit.value = 'true';
    fields.name.value = template.name || '';
    fields.description.value = template.description || '';
    fields.issueType.value = template.issueType || 'task';
    fields.priority.value = template.priority || 'Medium';
    fields.tags.value = Array.isArray(template.tags) ? template.tags.join(',') : '';
    fields.titleTemplate.value = template.titleTemplate || '';
    fields.descriptionTemplate.value = template.descriptionTemplate || '';
    fields.variables.value = JSON.stringify(template.variables || [], null, 2);
    setFeedback(`Editing ${template.name}.`, false);
  }

  function resetForm() {
    form.reset();
    fields.id.value = '';
    fields.isEdit.value = 'false';
  }

  function normalizeVariables(raw) {
    if (!raw || raw.trim() === '') return [];
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) throw new Error('Variables JSON must be an array');
    return parsed;
  }

  async function refreshTemplates() {
    const response = await fetch(apiBase);
    if (!response.ok) {
      throw new Error(await response.text());
    }
    templates = await response.json();
    renderList();
  }

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const isEdit = fields.isEdit.value === 'true' && fields.id.value.trim() !== '';

    try {
      const payload = {
        id: fields.id.value.trim() || null,
        name: fields.name.value.trim(),
        description: fields.description.value.trim(),
        issueType: fields.issueType.value.trim(),
        priority: fields.priority.value,
        tags: fields.tags.value
          .split(',')
          .map((v) => v.trim())
          .filter((v) => v.length > 0),
        titleTemplate: fields.titleTemplate.value,
        descriptionTemplate: fields.descriptionTemplate.value,
        variables: normalizeVariables(fields.variables.value)
      };

      const url = isEdit ? `${apiBase}/${encodeURIComponent(fields.id.value.trim())}` : apiBase;
      const method = isEdit ? 'PUT' : 'POST';
      const response = await fetch(url, {
        method,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });
      if (!response.ok) {
        throw new Error(await response.text());
      }

      await refreshTemplates();
      setFeedback(isEdit ? 'Template updated.' : 'Template created.', false);
      if (!isEdit) {
        resetForm();
      }
    } catch (err) {
      setFeedback(`Save failed: ${String(err)}`, true);
    }
  });

  if (resetButton) {
    resetButton.addEventListener('click', () => {
      resetForm();
      setFeedback('', false);
    });
  }

  templates = parseInitialTemplates();
  renderList();
}
