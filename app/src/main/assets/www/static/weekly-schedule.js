'use strict';
(() => {
  const DAYS=[
    {id:1,name:'Lunes',short:'L'},
    {id:2,name:'Martes',short:'M'},
    {id:3,name:'Miércoles',short:'X'},
    {id:4,name:'Jueves',short:'J'},
    {id:5,name:'Viernes',short:'V'}
  ];

  function readDb(){
    const db=JSON.parse(window.AulaEvidenciaStore.exportJson());
    db.weeklySchedule=Array.isArray(db.weeklySchedule)?db.weeklySchedule:[];
    return db;
  }
  function writeDb(db){
    window.AulaEvidenciaStore.importJson(JSON.stringify(db));
  }
  function entries(groupId=null){
    const db=readDb();
    const activeIds=new Set((db.groups||[]).filter(g=>!g.archived).map(g=>Number(g.id)));
    return db.weeklySchedule
      .filter(x=>activeIds.has(Number(x.group_id)) && (groupId===null || Number(x.group_id)===Number(groupId)))
      .sort((a,b)=>Number(a.day)-Number(b.day)||(a.start||'').localeCompare(b.start||'')||(a.end||'').localeCompare(b.end||''));
  }
  function nextId(db){
    return (db.weeklySchedule||[]).reduce((m,x)=>Math.max(m,Number(x.id)||0),0)+1;
  }
  function groupById(id){ return state.groups.find(g=>Number(g.id)===Number(id)); }
  function dayName(day){ return DAYS.find(d=>d.id===Number(day))?.name||''; }
  function timeRange(x){ return `${esc(x.start||'')}–${esc(x.end||'')}`; }

  const originalTodayPage=todayPage;
  const originalBindToday=bindToday;
  const originalNavButtons=navButtons;
  const originalMobileNav=mobileNav;
  const originalGroupPage=groupPage;
  const originalBindGroup=bindGroup;

  navButtons=function(){
    return originalNavButtons().replace(/<span>⌂<\/span>Hoy<\/button>/,'<span>▤</span>Horario</button>');
  };
  mobileNav=function(){
    return originalMobileNav().replace(/>Hoy<\/button>/,'>Horario</button>');
  };

  todayPage=async function(){
    const all=entries();
    const grouped=Object.fromEntries(DAYS.map(d=>[d.id,all.filter(x=>Number(x.day)===d.id)]));
    const count=all.length;
    return `<div class="between row"><div><h1 class="page-title">Horario semanal</h1><div class="subtitle">Todos los grupos · ${count} sesiones semanales</div></div><button class="btn" id="goGroups">Editar por grupo</button></div>
      <div class="weekly-schedule-grid">${DAYS.map(d=>`<section class="schedule-day card"><div class="schedule-day-title"><b>${d.name}</b><span class="pill">${grouped[d.id].length}</span></div><div class="schedule-day-items">${grouped[d.id].length?grouped[d.id].map(x=>{const g=groupById(x.group_id);return `<button class="schedule-session" data-schedule-group="${x.group_id}" style="--group-color:${esc(g?.color||'#173b67')}"><span class="schedule-time">${timeRange(x)}</span><b>${esc(g?.name||'Grupo')}</b><span>${esc(g?.subject||'')}</span>${x.room?`<small>Aula ${esc(x.room)}</small>`:''}</button>`}).join(''):'<div class="schedule-empty">Sin clases</div>'}</div></section>`).join('')}</div>
      <div class="card section-gap"><h3>Configurar horario</h3><p class="muted">Abre un grupo y entra en la pestaña <b>Horario</b> para añadir, modificar o eliminar sus sesiones semanales.</p></div>`;
  };

  bindToday=function(){
    const go=document.querySelector('#goGroups');
    if(go)go.onclick=()=>{state.page='groups';state.groupId=null;render()};
    document.querySelectorAll('[data-schedule-group]').forEach(b=>b.onclick=()=>selectGroup(Number(b.dataset.scheduleGroup),'schedule'));
  };

  function scheduleTabs(active){
    return [['gradebook','Cuaderno'],['evaluation','Evaluación'],['students','Alumnado'],['attendance','Asistencia'],['analysis','Análisis'],['schedule','Horario']]
      .map(([k,l])=>`<button class="tab ${active===k?'active':''}" data-gtab="${k}">${l}</button>`).join('');
  }

  async function scheduleTab(g){
    const list=entries(g.id);
    return `<div class="toolbar"><div class="left"><b>${list.length} sesiones semanales</b><span class="muted">${esc(g.name)}</span></div><div class="right"><button class="btn primary" id="addScheduleEntry">+ Añadir clase</button></div></div>
      <div class="card schedule-editor">${list.length?`<div class="schedule-list">${list.map(x=>`<div class="schedule-row" data-schedule-id="${x.id}"><div class="schedule-day-badge">${esc(DAYS.find(d=>d.id===Number(x.day))?.short||'')}</div><div class="grow"><b>${esc(dayName(x.day))} · ${timeRange(x)}</b><div class="muted">${x.room?`Aula ${esc(x.room)}`:'Sin aula'}${x.note?` · ${esc(x.note)}`:''}</div></div><div class="row"><button class="btn" data-edit-schedule="${x.id}">Editar</button><button class="btn danger" data-delete-schedule="${x.id}">Eliminar</button></div></div>`).join('')}</div>`:'<div class="schedule-empty-large"><b>Aún no hay clases configuradas.</b><span>Añade las sesiones semanales de este grupo.</span></div>'}</div>`;
  }

  groupPage=async function(){
    if(state.groupTab==='schedule'){
      const g=await api(`/api/groups/${state.groupId}`);
      return `<div class="between row group-heading"><div><button class="btn ghost" id="backGroups">← Grupos</button><h1 class="page-title">${esc(g.name)}</h1><div class="subtitle">${esc(g.subject)} · ${esc(g.course)}</div></div><span class="pill">${esc(g.curriculum?.name||'Sin currículo')}</span></div><div class="tabs">${scheduleTabs('schedule')}</div>${await scheduleTab(g)}`;
    }
    let html=await originalGroupPage();
    html=html.replace(/(<div class="tabs">)([\s\S]*?)(<\/div>)/,(_,a,b,c)=>`${a}${b}<button class="tab" data-gtab="schedule">Horario</button>${c}`);
    return html;
  };

  bindGroup=function(){
    if(state.groupTab!=='schedule') return originalBindGroup();
    document.querySelector('#backGroups').onclick=()=>{state.groupId=null;render()};
    document.querySelectorAll('[data-gtab]').forEach(b=>b.onclick=()=>{state.groupTab=b.dataset.gtab;render()});
    document.querySelector('#addScheduleEntry').onclick=()=>scheduleEntryModal(null);
    document.querySelectorAll('[data-edit-schedule]').forEach(b=>b.onclick=()=>scheduleEntryModal(Number(b.dataset.editSchedule)));
    document.querySelectorAll('[data-delete-schedule]').forEach(b=>b.onclick=()=>deleteScheduleEntry(Number(b.dataset.deleteSchedule)));
  };

  function scheduleEntryModal(id){
    const current=id?entries(state.groupId).find(x=>Number(x.id)===Number(id)):null;
    const b=modal(`<h2>${current?'Editar clase':'Añadir clase semanal'}</h2>
      <div class="field"><label>Día</label><select id="schDay">${DAYS.map(d=>`<option value="${d.id}" ${Number(current?.day||1)===d.id?'selected':''}>${d.name}</option>`).join('')}</select></div>
      <div class="row"><div class="field grow"><label>Inicio</label><input type="time" id="schStart" value="${esc(current?.start||'08:00')}"></div><div class="field grow"><label>Fin</label><input type="time" id="schEnd" value="${esc(current?.end||'09:00')}"></div></div>
      <div class="field"><label>Aula (opcional)</label><input id="schRoom" value="${esc(current?.room||'')}" placeholder="Laboratorio / 2.14"></div>
      <div class="field"><label>Nota (opcional)</label><input id="schNote" value="${esc(current?.note||'')}" placeholder="Desdoble, guardia, etc."></div>
      <div class="row end"><button class="btn" id="schCancel">Cancelar</button><button class="btn primary" id="schSave">Guardar</button></div>`);
    b.querySelector('#schCancel').onclick=()=>closeModal(b);
    b.querySelector('#schSave').onclick=()=>{
      try{
        const day=Number(b.querySelector('#schDay').value),start=b.querySelector('#schStart').value,end=b.querySelector('#schEnd').value;
        if(!start||!end)throw new Error('Indica la hora de inicio y fin');
        if(end<=start)throw new Error('La hora de fin debe ser posterior al inicio');
        const db=readDb();
        const clash=(db.weeklySchedule||[]).find(x=>Number(x.id)!==Number(id)&&Number(x.day)===day&&start<x.end&&end>x.start);
        if(clash){
          const cg=groupById(clash.group_id);
          if(!confirm(`Coincide con ${cg?.name||'otro grupo'} (${clash.start}–${clash.end}). ¿Guardar de todas formas?`))return;
        }
        const item={id:id||nextId(db),group_id:Number(state.groupId),day,start,end,room:b.querySelector('#schRoom').value.trim(),note:b.querySelector('#schNote').value.trim()};
        const idx=db.weeklySchedule.findIndex(x=>Number(x.id)===Number(id));
        if(idx>=0)db.weeklySchedule[idx]=item;else db.weeklySchedule.push(item);
        writeDb(db);closeModal(b);toast('Horario guardado');render();
      }catch(e){toast(e.message)}
    };
  }

  function deleteScheduleEntry(id){
    const x=entries(state.groupId).find(e=>Number(e.id)===Number(id));
    if(!x||!confirm(`Eliminar ${dayName(x.day)} ${x.start}–${x.end}?`))return;
    const db=readDb();
    db.weeklySchedule=(db.weeklySchedule||[]).filter(e=>Number(e.id)!==Number(id));
    writeDb(db);toast('Clase eliminada');render();
  }

  const style=document.createElement('style');
  style.textContent=`
    .weekly-schedule-grid{display:grid;grid-template-columns:repeat(5,minmax(170px,1fr));gap:12px;margin-top:18px;overflow-x:auto;padding-bottom:6px}
    .schedule-day{padding:0;min-width:170px;overflow:hidden}
    .schedule-day-title{display:flex;justify-content:space-between;align-items:center;padding:12px 14px;border-bottom:1px solid rgba(0,0,0,.08)}
    .schedule-day-items{display:flex;flex-direction:column;gap:8px;padding:10px}
    .schedule-session{display:flex;flex-direction:column;align-items:flex-start;text-align:left;border:0;border-left:5px solid var(--group-color);border-radius:8px;padding:9px 10px;background:rgba(0,0,0,.035);cursor:pointer;width:100%}
    .schedule-session:hover{background:rgba(0,0,0,.065)}
    .schedule-time{font-variant-numeric:tabular-nums;font-weight:700;font-size:.84rem}
    .schedule-session small{opacity:.72;margin-top:2px}
    .schedule-empty{padding:18px 8px;text-align:center;opacity:.55}
    .schedule-list{display:flex;flex-direction:column}
    .schedule-row{display:flex;gap:12px;align-items:center;padding:12px;border-bottom:1px solid rgba(0,0,0,.08)}
    .schedule-row:last-child{border-bottom:0}
    .schedule-day-badge{width:36px;height:36px;border-radius:10px;display:grid;place-items:center;font-weight:800;background:rgba(23,59,103,.1)}
    .schedule-empty-large{display:flex;flex-direction:column;gap:5px;align-items:center;text-align:center;padding:34px 16px;opacity:.7}
    @media(max-width:800px){.weekly-schedule-grid{grid-template-columns:repeat(5,180px)}.schedule-row{align-items:flex-start;flex-wrap:wrap}.schedule-row>.row{margin-left:48px}}
  `;
  document.head.appendChild(style);

  // La aplicación ya ha realizado su primer render al cargarse app.js. Re-renderizamos
  // para sustituir la pantalla inicial "Hoy" por el horario semanal.
  if(state.page==='today') render();
})();
