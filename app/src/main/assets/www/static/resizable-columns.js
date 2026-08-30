'use strict';
(() => {
  const STORAGE_KEY='aulaevidencia_gradebook_column_widths_v1';
  const MIN=54, MAX=520;
  let widths={};
  try { widths=JSON.parse(localStorage.getItem(STORAGE_KEY)||'{}')||{}; } catch(_){ widths={}; }

  function isGradebookTable(table){
    const text=(table.closest('section,main,div')?.innerText||table.innerText||'').toLowerCase();
    return text.includes('cuaderno') || text.includes('criterio') || table.querySelector('input[type="number"]');
  }
  function tableKey(table){
    const headers=[...table.querySelectorAll('thead th')].map(th=>(th.innerText||'').trim()).join('|');
    return 't:'+headers.slice(0,400);
  }
  function save(){ try { localStorage.setItem(STORAGE_KEY,JSON.stringify(widths)); } catch(_){} }
  function applyWidth(table,index,width){
    const px=Math.max(MIN,Math.min(MAX,Math.round(width)));
    table.querySelectorAll('tr').forEach(row=>{
      const cell=row.children[index];
      if(cell){ cell.style.width=px+'px'; cell.style.minWidth=px+'px'; cell.style.maxWidth=px+'px'; }
    });
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
      const handle=document.createElement('span');
      handle.className='ae-col-resizer';
      handle.title='Arrastra para cambiar el ancho de la columna';
      handle.setAttribute('aria-label','Cambiar ancho de columna');
      th.appendChild(handle);
      let startX=0,startW=0;
      const move=e=>{
        const x=e.touches?.[0]?.clientX ?? e.clientX;
        applyWidth(table,index,startW+(x-startX));
      };
      const end=()=>{
        document.removeEventListener('mousemove',move); document.removeEventListener('mouseup',end);
        document.removeEventListener('touchmove',move); document.removeEventListener('touchend',end);
        const w=Math.round(th.getBoundingClientRect().width);
        widths[key]??={}; widths[key][index]=w; save();
        document.body.classList.remove('ae-resizing');
      };
      const start=e=>{
        e.preventDefault(); e.stopPropagation();
        startX=e.touches?.[0]?.clientX ?? e.clientX;
        startW=th.getBoundingClientRect().width;
        document.body.classList.add('ae-resizing');
        document.addEventListener('mousemove',move); document.addEventListener('mouseup',end);
        document.addEventListener('touchmove',move,{passive:false}); document.addEventListener('touchend',end);
      };
      handle.addEventListener('mousedown',start);
      handle.addEventListener('touchstart',start,{passive:false});
      handle.addEventListener('dblclick',e=>{
        e.preventDefault(); e.stopPropagation();
        table.querySelectorAll('tr').forEach(row=>{ const c=row.children[index]; if(c){c.style.width='';c.style.minWidth='';c.style.maxWidth='';} });
        if(widths[key]) { delete widths[key][index]; save(); }
      });
    });
  }
  function scan(){ document.querySelectorAll('table').forEach(enhance); }
  const style=document.createElement('style');
  style.textContent=`
    .ae-col-resizer{position:absolute;top:0;right:-5px;width:12px;height:100%;cursor:col-resize;touch-action:none;z-index:12;border-right:2px solid transparent}
    .ae-col-resizer:active,.ae-col-resizer:hover{border-right-color:currentColor}
    body.ae-resizing{user-select:none;cursor:col-resize}
    body.ae-resizing *{cursor:col-resize!important}
  `;
  document.head.appendChild(style);
  new MutationObserver(scan).observe(document.getElementById('app')||document.body,{childList:true,subtree:true});
  scan();
})();
