'use strict';
(() => {
  const SELECTOR = 'table input[type="number"]';

  function isGradebookInput(input) {
    const table = input.closest('table');
    if (!table) return false;
    const context = (table.closest('section,main,div')?.innerText || table.innerText || '').toLowerCase();
    return context.includes('cuaderno');
  }

  function normalize(input) {
    if (!isGradebookInput(input) || input.value === '') return;
    let value = Number(String(input.value).replace(',', '.'));
    if (!Number.isFinite(value)) { input.value = ''; return; }
    value = Math.max(0, Math.min(10, value));
    input.value = value.toFixed(2);
  }

  function enhance(input) {
    if (input.dataset.aeGradebookScore === '1' || !isGradebookInput(input)) return;
    input.dataset.aeGradebookScore = '1';
    input.min = '0';
    input.max = '10';
    input.step = '0.01';
    input.inputMode = 'decimal';
    input.addEventListener('change', () => normalize(input));
    input.addEventListener('blur', () => normalize(input));
    input.addEventListener('input', () => {
      if (input.value === '') return;
      const value = Number(String(input.value).replace(',', '.'));
      if (Number.isFinite(value) && value > 10) input.value = '10.00';
      if (Number.isFinite(value) && value < 0) input.value = '0.00';
    });
    if (input.value !== '') normalize(input);
  }

  function scan() { document.querySelectorAll(SELECTOR).forEach(enhance); }
  new MutationObserver(scan).observe(document.getElementById('app') || document.body, {childList:true, subtree:true});
  scan();
})();
