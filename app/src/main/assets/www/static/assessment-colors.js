'use strict';
(() => {
  const baseApi = window.localApi;
  if (typeof baseApi !== 'function') return;

  const PALETTE = [
    '#DCEBFF','#E4F4E8','#FFF0CC','#FADDDD','#E9E0FA','#DDF4F4',
    '#FBE3F0','#EAE6D9','#CFE3F8','#D9EBCB','#F8D8B8','#E2DBF2'
  ];
  let lastGradebookColumns = [];

  const storeDb = () => {
    try { return window.AulaEvidenciaStore ? JSON.parse(window.AulaEvidenciaStore.exportJson()) : null; }
    catch (_) { return null; }
  };
  const saveDb = db => {
    try { if (window.AulaEvidenciaStore) window.AulaEvidenciaStore.importJson(JSON.stringify(db)); }
    catch (_) {}
  };
  const defaultColor = id => PALETTE[Math.abs(Number(id)||0) % PALETTE.length];
  const getColor = (db,id) => db?.assessments?.find(a=>a.id===Number(id))?.color || defaultColor(id);
  const setColor = (id,color) => {
    const db=storeDb(); if(!db) return;
    const a=db.assessments?.find(x=>x.id===Number(id)); if(!a) return;
    a.color = PALETTE.includes(color) ? color : defaultColor(id);
    saveDb(db);
  };

  function currentPaletteColor(){
    const modal=document.querySelector('#saveAssessment')?.closest('.modal, .modal-card, [role="dialog"]') || document.querySelector('#saveAssessment')?.parentElement?.parentElement;
    return modal?.dataset?.assessmentColor || null;
  }

  window.localApi = async function(url,opts={}){
    const method=(opts.method||'GET').toUpperCase();
    const u=new URL(url,'https://local.aulaevidencia');
    const path=u.pathname;

    if(method==='POST' && path==='/api/assessments/configured'){
      const color=currentPaletteColor();
      const out=await baseApi(url,opts);
      const id=out?.assessment?.id;
      if(id) setColor(id,color||defaultColor(id));
      return out;
    }
    const cfg=path.match(/^\/api\/assessments\/(\d+)\/configuration$/);
    if(method==='PUT' && cfg){
      const color=currentPaletteColor();
      const out=await baseApi(url,opts);
      setColor(Number(cfg[1]),color||getColor(storeDb(),Number(cfg[1])));
      return out;
    }

    const out=await baseApi(url,opts);
    const db=storeDb();

    if(method==='GET' && /^\/api\/groups\/\d+\/gradebook$/.test(path) && u.searchParams.get('view')!=='criteria' && out?.columns){
      lastGradebookColumns=out.columns.map(c=>({...c,assessmentColor:c.assessmentId?getColor(db,c.assessmentId):null}));
      out.columns=lastGradebookColumns;
    }
    if(method==='GET' && /^\/api\/groups\/\d+\/assessments$/.test(path) && Array.isArray(out)){
      return out.map(a=>({...a,color:getColor(db,a.id)}));
    }
    const one=path.match(/^\/api\/assessments\/(\d+)$/);
    if(method==='GET' && one && out && typeof out==='object') out.color=getColor(db,Number(one[1]));
    return out;
  };

  function paletteMarkup(selected){
    return `<div class="field ae-assessment-color-field"><label>Color de la prueba</label><div class="ae-color-palette" role="group" aria-label="Paleta de colores de la prueba">${PALETTE.map(c=>`<button type="button" class="ae-color-swatch ${c===selected?'selected':''}" data-ae-color="${c}" style="background:${c}" title="Elegir este color" aria-label="Elegir color ${c}"></button>`).join('')}</div><div class="muted">Todas las preguntas y el Total de esta prueba usarán este color en el Cuaderno.</div></div>`;
  }

  function inferAssessmentColor(container){
    const db=storeDb();
    const title=container.querySelector('#aTitle')?.value?.trim();
    const period=Number(container.querySelector('#aPeriod')?.value||0);
    const found=db?.assessments?.find(a=>a.title===title && (!period || a.period_id===period));
    return found ? getColor(db,found.id) : PALETTE[0];
  }

  function injectPalette(){
    const save=document.querySelector('#saveAssessment');
    if(!save) return;
    const container=save.closest('.modal, .modal-card, [role="dialog"]') || save.parentElement?.parentElement;
    if(!container || container.querySelector('.ae-assessment-color-field')) return;
    const selected=inferAssessmentColor(container);
    container.dataset.assessmentColor=selected;
    const anchor=container.querySelector('#aType')?.closest('.field') || container.querySelector('#aTitle')?.closest('.field');
    if(anchor) anchor.insertAdjacentHTML('afterend',paletteMarkup(selected));
    else save.parentElement?.insertAdjacentHTML('beforebegin',paletteMarkup(selected));
    container.querySelectorAll('.ae-color-swatch').forEach(btn=>btn.addEventListener('click',()=>{
      container.dataset.assessmentColor=btn.dataset.aeColor;
      container.querySelectorAll('.ae-color-swatch').forEach(x=>x.classList.toggle('selected',x===btn));
    }));
  }

  function colorGradebook(){
    const table=[...document.querySelectorAll('.table-wrap table')].find(t=>t.querySelector('.cell-input'));
    if(!table || !lastGradebookColumns.length) return;
    const headers=[...table.querySelectorAll('thead th')].slice(1);
    headers.forEach((th,i)=>{
      const c=lastGradebookColumns[i]; if(!c?.assessmentColor) return;
      th.style.backgroundColor=c.assessmentColor;
      th.style.borderTop=`4px solid ${c.assessmentColor}`;
      table.querySelectorAll('tbody tr').forEach(row=>{
        const td=row.children[i+1]; if(!td) return;
        td.style.backgroundColor=c.assessmentColor;
      });
    });
  }

  function decorateAssessmentRows(){
    const db=storeDb();
    document.querySelectorAll('.assessment-row').forEach(row=>{
      if(row.querySelector('.ae-assessment-dot')) return;
      const title=row.querySelector('b')?.textContent?.trim();
      const candidates=(db?.assessments||[]).filter(a=>a.title===title);
      if(!candidates.length) return;
      const color=getColor(db,candidates[0].id);
      const dot=document.createElement('span'); dot.className='ae-assessment-dot'; dot.style.background=color;
      row.querySelector('b')?.prepend(dot);
    });
  }

  function scan(){ injectPalette(); colorGradebook(); decorateAssessmentRows(); }
  const style=document.createElement('style');
  style.textContent=`
    .ae-color-palette{display:flex;flex-wrap:wrap;gap:8px;margin:7px 0 6px}
    .ae-color-swatch{width:34px;height:34px;border:2px solid rgba(0,0,0,.14);border-radius:9px;padding:0;box-shadow:none}
    .ae-color-swatch.selected{outline:3px solid #173b67;outline-offset:2px}
    .ae-assessment-dot{display:inline-block;width:12px;height:12px;border-radius:3px;margin-right:7px;vertical-align:baseline;border:1px solid rgba(0,0,0,.15)}
    .table-wrap table td,.table-wrap table th{transition:background-color .12s ease}
  `;
  document.head.appendChild(style);
  new MutationObserver(scan).observe(document.getElementById('app')||document.body,{childList:true,subtree:true});
  scan();
})();
