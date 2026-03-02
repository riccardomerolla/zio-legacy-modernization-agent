const selector = document.getElementById('issueTemplateId');
const dataNode = document.getElementById('issue-create-template-data');
const variableRoot = document.getElementById('issue-template-variables');

if (selector && dataNode && variableRoot) {
  let templates = [];
  try {
    templates = JSON.parse(dataNode.textContent || '[]');
  } catch (_) {
    templates = [];
  }

  const byId = new Map(templates.map((t) => [t.id, t]));
  const titleInput = document.getElementById('title');
  const descriptionInput = document.getElementById('description');
  const issueTypeInput = document.getElementById('issueType');
  const priorityInput = document.getElementById('priority');
  const tagsInput = document.getElementById('tags');

  function renderTemplate(raw, values) {
    if (!raw) return '';
    return raw.replace(/\{\{\s*([a-zA-Z0-9_-]+)\s*\}\}/g, (_, key) => values[key] ?? '');
  }

  function readVariableValues() {
    const values = {};
    variableRoot.querySelectorAll('input[data-template-var]').forEach((input) => {
      values[input.dataset.templateVar] = input.value;
    });
    return values;
  }

  function applyTemplate(template) {
    if (!template) return;
    if (issueTypeInput) issueTypeInput.value = template.issueType || 'task';
    if (priorityInput) priorityInput.value = (template.priority || 'Medium').toLowerCase();
    if (tagsInput) tagsInput.value = Array.isArray(template.tags) ? template.tags.join(',') : '';

    const values = readVariableValues();
    if (titleInput) titleInput.value = renderTemplate(template.titleTemplate || '', values);
    if (descriptionInput) descriptionInput.value = renderTemplate(template.descriptionTemplate || '', values);
  }

  function renderVariables(template) {
    variableRoot.innerHTML = '';
    if (!template || !Array.isArray(template.variables) || template.variables.length === 0) {
      return;
    }

    template.variables.forEach((v) => {
      const wrap = document.createElement('div');
      const label = document.createElement('label');
      label.className = 'mb-2 block text-sm font-semibold text-slate-200';
      label.textContent = v.label || v.name;
      label.setAttribute('for', `template-var-${v.name}`);

      const input = document.createElement('input');
      input.type = 'text';
      input.id = `template-var-${v.name}`;
      input.className = 'w-full rounded-lg border border-white/15 bg-slate-800/80 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-500 focus:border-indigo-400/40 focus:outline-none';
      input.dataset.templateVar = v.name;
      input.placeholder = v.description || '';
      input.value = v.defaultValue || '';
      if (v.required !== false) {
        input.required = true;
      }

      input.addEventListener('input', () => applyTemplate(template));

      wrap.appendChild(label);
      wrap.appendChild(input);
      variableRoot.appendChild(wrap);
    });
  }

  selector.addEventListener('change', () => {
    const template = byId.get(selector.value);
    renderVariables(template);
    applyTemplate(template);
  });
}
