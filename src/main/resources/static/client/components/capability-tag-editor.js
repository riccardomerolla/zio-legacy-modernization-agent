const EDITOR_SELECTOR = '[data-capability-editor="true"]';
const CAPABILITIES_ENDPOINT = '/api/agents/capabilities';

function normalize(value) {
  return String(value || '').trim().toLowerCase();
}

function parseCsv(value) {
  return String(value || '')
    .split(',')
    .map((v) => normalize(v))
    .filter((v) => v.length > 0);
}

async function fetchCapabilitySuggestions() {
  try {
    const response = await fetch(CAPABILITIES_ENDPOINT, { headers: { Accept: 'application/json' } });
    if (!response.ok) return [];
    const payload = await response.json();
    if (!Array.isArray(payload)) return [];
    return payload.map((v) => normalize(v)).filter((v) => v.length > 0);
  } catch (_) {
    return [];
  }
}

function buildChip(value, onRemove) {
  const chip = document.createElement('span');
  chip.className = 'inline-flex items-center gap-1 rounded-full bg-indigo-500/20 px-2 py-0.5 text-xs text-indigo-100';

  const label = document.createElement('span');
  label.textContent = value;

  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'rounded px-1 text-indigo-200 hover:bg-indigo-400/20';
  button.textContent = 'x';
  button.addEventListener('click', () => onRemove(value));

  chip.appendChild(label);
  chip.appendChild(button);
  return chip;
}

function mountEditor(root, suggestions) {
  const hidden = root.querySelector('input[type="hidden"]');
  const chipsRoot = root.querySelector('[data-capability-chips="true"]');
  const input = root.querySelector('[data-capability-input="true"]');
  if (!hidden || !chipsRoot || !input) return;

  const values = new Set(parseCsv(hidden.value));

  const datalistId = `capability-autocomplete-${Math.random().toString(36).slice(2, 9)}`;
  const datalist = document.createElement('datalist');
  datalist.id = datalistId;
  suggestions.forEach((value) => {
    const option = document.createElement('option');
    option.value = value;
    datalist.appendChild(option);
  });
  root.appendChild(datalist);
  input.setAttribute('list', datalistId);

  function syncHidden() {
    hidden.value = Array.from(values).join(',');
  }

  function render() {
    chipsRoot.innerHTML = '';
    Array.from(values)
      .sort()
      .forEach((value) => {
        chipsRoot.appendChild(
          buildChip(value, (toRemove) => {
            values.delete(toRemove);
            syncHidden();
            render();
          }),
        );
      });
  }

  function addValue(raw) {
    const value = normalize(raw);
    if (!value) return;
    values.add(value);
    syncHidden();
    render();
    input.value = '';
  }

  input.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' || event.key === ',') {
      event.preventDefault();
      addValue(input.value);
    }

    if (event.key === 'Backspace' && !input.value && values.size > 0) {
      const items = Array.from(values);
      values.delete(items[items.length - 1]);
      syncHidden();
      render();
    }
  });

  input.addEventListener('blur', () => {
    if (input.value.trim().length > 0) {
      addValue(input.value);
    }
  });

  syncHidden();
  render();
}

async function initializeEditors() {
  const editors = Array.from(document.querySelectorAll(EDITOR_SELECTOR));
  if (editors.length === 0) return;

  const suggestions = await fetchCapabilitySuggestions();
  editors.forEach((editor) => mountEditor(editor, suggestions));
}

initializeEditors();
