'use strict';
(() => {
  const STORAGE_KEY='aulaevidencia_gradebook_column_widths_v1';
  const MIN=54, MAX=520;
  let widths={};
  let resizeMode=false;
  try { widths=JSON.parse(localStorage.getItem(STORAGE_KEY)||'{}')||{}; } catch(_){ widths={}; }

  function isGradebookTable(table){
    const context=(table.closest('section,main,div')?.innerText||table.innerText||'').toLowerCase();
    return context.includes('cuaderno') && !!table.querySelector('input[type="number"]');
  }

  function tableKey(table){
    const headers=[...table.querySelectorAll('thead th')].map(th=>(th.innerText||'').trim()).join('|');
    return 't:'+headers.slice(0,400);
  }

  function save(){
    try { localStorage.setItem(STORAGE_KEY,JSON.stringify(widths)); } catch(_){}
  }

  function applyWidth(table,index,width){
    const px=Math.max(MIN,Math.min(MAX,Math.round(width)));
    table.querySelectorAll('tr').forEach(row=>{
      const cell=row.children[index];
      if(cell){
        cell.style.width=px+'px';
        cell.style.minWidth=px+'px';
        cell.style.maxWidth=px+'px';
      }
    });
  }

  function updateButton(){
    const btn=document.getElementById('ae-resize-columns-toggle');
    const hasGradebook=[...document.querySelectorAll('table')].some(isGradebookTable);
    if(btn) {
      btn.hidden=!hasGradebook;
      btn.textContent=resizeMode?'Finalizar ajuste':'Ajustar columnas';
      btn.setAttribute('aria-pressed',resizeMode?'true':'false');
      btn.title=resizeMode?'Desactiva el ajuste de columnas':'Activa los tiradores para modificar el ancho de las columnas';
    }
    document.body.classList.toggle('ae-resize-mode',resizeMode && hasGradebook);
    if(!hasGradebook && resizeMode) resizeMode=false;
  }

  function ensureToggleButton(){
    let btn=document.getElementById('ae-resize-columns-toggle');
    if(btn) return btn;
    btn=document.createElement('button');
    btn.id='ae-resize-columns-toggle';
    btn.type='button';
    btn.className='ae-resize-toggle';
    btn.hidden=true;
    btn.textContent='Ajustar columnas';
    btn.setAttribute('aria-pressed','false');
    btn.addEventListener('click',()=>{
      resizeMode=!resizeMode;
      updateButton();
    });
    document.body.appendChild(btn);
    return btn;
  }

  function enhance(table){
    if(table.dataset.aeResizable==='1' || !isGradebookTable(table)) return;
    const headers=[...table.querySelectorAll('thead th')];
    if(!headers.length) return;
    table.dataset.aeResizable='1';
    table.style.tableLayout='fixed';
    const key=tableKey(table);

    headers.forEach((th,index)=>{
      th.style.position='relative';
      const saved=widths[key]?.[index];
      if(saved) applyWidth(table,index,saved);

      const handle=document.createElement('button');
      handle.type='button';
      handle.className='ae-col-resizer';
      handle.title='Arrastra para cambiar el ancho de esta columna';
      handle.setAttribute('aria-label','Cambiar ancho de columna');
      th.appendChild(handle);

      let startX=0,startW=0;
      const move=e=>{
        if(!resizeMode) return;
        if(e.cancelable) e.preventDefault();
        const x=e.touches?.[0]?.clientX ?? e.clientX;
        applyWidth(table,index,startW+(x-startX));
      };
      const end=()=>{
        document.removeEventListener('mousemove',move);
        document.removeEventListener('mouseup',end);
        document.removeEventListener('touchmove',move);
        document.removeEventListener('touchend',end);
        if(resizeMode){
          const w=Math.round(th.getBoundingClientRect().width);
          widths[key]??={};
          widths[key][index]=w;
          save();
        }
        document.body.classList.remove('ae-resizing');
      };
      const start=e=>{
        if(!resizeMode) return;
        e.preventDefault();
        e.stopPropagation();
        startX=e.touches?.[0]?.clientX ?? e.clientX;
        startW=th.getBoundingClientRect().width;
        document.body.classList.add('ae-resizing');
        document.addEventListener('mousemove',move);
        document.addEventListener('mouseup',end);
        document.addEventListener('touchmove',move,{passive:false});
        document.addEventListener('touchend',end);
      };

      handle.addEventListener('mousedown',start);
      handle.addEventListener('touchstart',start,{passive:false});
      handle.addEventListener('dblclick',e=>{
        if(!resizeMode) return;
        e.preventDefault();
        e.stopPropagation();
        table.querySelectorAll('tr').forEach(row=>{
          const c=row.children[index];
          if(c){ c.style.width=''; c.style.minWidth=''; c.style.maxWidth=''; }
        });
        if(widths[key]){
          delete widths[key][index];
          save();
        }
      });
    });
  }

  function scan(){
    ensureToggleButton();
    document.querySelectorAll('table').forEach(enhance);
    updateButton();
  }

  const style=document.createElement('style');
  style.textContent=`
    .ae-resize-toggle{position:fixed;right:16px;bottom:82px;z-index:80;padding:10px 14px;border-radius:999px;border:1px solid rgba(0,0,0,.2);background:#fff;box-shadow:0 3px 12px rgba(0,0,0,.18);font-weight:600;cursor:pointer}
    .ae-resize-toggle[aria-pressed="true"]{outline:3px solid rgba(23,59,103,.2)}
    .ae-col-resizer{display:none;position:absolute;top:50%;right:-10px;transform:translateY(-50%);width:24px;height:34px;padding:0;border:1px solid currentColor;border-radius:8px;background:rgba(255,255,255,.92);cursor:col-resize;touch-action:none;z-index:12}
    .ae-col-resizer::before{content:'⋮';font-size:22px;line-height:28px;display:block}
    body.ae-resize-mode .ae-col-resizer{display:block}
    body.ae-resize-mode table[data-ae-resizable="1"] thead th{outline:1px dashed rgba(23,59,103,.28)}
    body.ae-resizing{user-select:none;cursor:col-resize}
    body.ae-resizing *{cursor:col-resize!important}
  `;
  document.head.appendChild(style);
  new MutationObserver(scan).observe(document.getElementById('app')||document.body,{childList:true,subtree:true});
  scan();
})();
