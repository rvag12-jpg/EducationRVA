'use strict';
(() => {
  const baseApi = window.localApi;
  if (typeof baseApi !== 'function') return;

  function hslToHex(h,s,l){
    s/=100; l/=100;
    const c=(1-Math.abs(2*l-1))*s, x=c*(1-Math.abs((h/60)%2-1)), m=l-c/2;
    let r=0,g=0,b=0;
    if(h<60){r=c;g=x;} else if(h<120){r=x;g=c;} else if(h<180){g=c;b=x;}
    else if(h<240){g=x;b=c;} else if(h<300){r=x;b=c;} else {r=c;b=x;}
    return '#'+[r,g,b].map(v=>Math.round((v+m)*255).toString(16).padStart(2,'0')).join('').toUpperCase();
  }

  // 32 tonos × 8 saturaciones × 4 luminosidades = 1024 colores.
  const PALETTE=[];
  for(let hi=0;hi<32;hi++){
    const h=hi*(360/32);
    for(let si=0;si<8;si++){
      const s=30+si*9;
      for(let li=0;li<4;li++){
        const l=34+li*15;
        PALETTE.push(hslToHex(h,s,l));
      }
    }
  }
  const COLOR_SET=new Set(PALETTE);
  let lastGradebookColumns = [];

  const storeDb = () => {
    try { return window.AulaEvidenciaStore ? JSON.parse(window.AulaEvidenciaStore.exportJson()) : null; }
    catch (_) { return null; }
  };
  const saveDb = db => {
    try { if (window.AulaEvidenciaStore) window.AulaEvidenciaStore.importJson(JSON.stringify(db)); }
    catch (_) {}
  };
  const validHex = c => typeof c==='string' && /^#[0-9A-Fa-f]{6}$/.test(c);
  const defaultColor = id => PALETTE[Math.abs(Number(id)||0) % PALETTE.length];
  const getColor = (db,id) => {
    const stored=db?.assessments?.find(a=>a.id===Number(id))?.color;
    return validHex(stored) ? stored.toUpperCase() : defaultColor(id);
  };
  const setColor = (id,color) => {
    const db=storeDb(); if(!db) return;
    const a=db.assessments?.find(x=>x.id===Number(id)); if(!a) return;
    a.color = validHex(color) ? color.toUpperCase() : defaultColor(id);
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
    const normalized=validHex(selected)?selected.toUpperCase():PALETTE[0];
    return `<div class="field ae-assessment-color-field"><label>Color de la prueba · 1024 colores</label><div class="ae-selected-color"><span class="ae-selected-preview" style="background:${normalized}"></span><code>${normalized}</code></div><div class="ae-color-palette" role="group" aria-label="Paleta de 1024 colores de la prueba">${PALETTE.map(c=>`<button type="button" class="ae-color-swatch ${c===normalized?'selected':''}" data-ae-color="${c}" style="background:${c}" title="${c}" aria-label="Elegir color ${c}"></button>`).join('')}</div><div class="muted">1024 colores disponibles. Todas las preguntas y el Total de esta prueba usarán el color elegido en el Cuaderno.</div></div>`;
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
      const color=btn.dataset.aeColor;
      container.dataset.assessmentColor=color;
      container.querySelectorAll('.ae-color-swatch').forEach(x=>x.classList.toggle('selected',x===btn));
      const preview=container.querySelector('.ae-selected-preview'); if(preview) preview.style.background=color;
      const code=container.querySelector('.ae-selected-color code'); if(code) code.textContent=color;
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
    .ae-selected-color{display:flex;align-items:center;gap:8px;margin:7px 0}
    .ae-selected-preview{width:28px;height:28px;border-radius:7px;border:1px solid rgba(0,0,0,.22)}
    .ae-selected-color code{font-size:12px}
    .ae-color-palette{display:grid;grid-template-columns:repeat(16,22px);gap:4px;max-height:220px;overflow:auto;padding:8px;margin:7px 0 6px;border:1px solid rgba(0,0,0,.14);border-radius:10px;background:#fff}
    .ae-color-swatch{width:22px;height:22px;border:1px solid rgba(0,0,0,.18);border-radius:4px;padding:0;box-shadow:none;min-width:22px}
    .ae-color-swatch.selected{outline:3px solid #173b67;outline-offset:1px;z-index:1}
    .ae-assessment-dot{display:inline-block;width:12px;height:12px;border-radius:3px;margin-right:7px;vertical-align:baseline;border:1px solid rgba(0,0,0,.15)}
    .table-wrap table td,.table-wrap table th{transition:background-color .12s ease}
    @media (max-width:600px){.ae-color-palette{grid-template-columns:repeat(12,22px);max-height:200px}}
  `;
  document.head.appendChild(style);
  new MutationObserver(scan).observe(document.getElementById('app')||document.body,{childList:true,subtree:true});
  scan();
})();
