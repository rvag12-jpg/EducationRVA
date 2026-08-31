'use strict';
(() => {
  const CENTER_KEY='docio_educational_center_v1';
  const style=document.createElement('style');
  style.textContent=`
    body{padding-top:64px}
    .docio-global-header{position:fixed;left:0;right:0;top:0;height:58px;z-index:110;display:flex;align-items:center;gap:10px;padding:0 14px;background:rgba(255,255,255,.96);border-bottom:1px solid rgba(0,0,0,.08);box-shadow:0 2px 10px rgba(0,0,0,.06)}
    .docio-home-btn{width:40px;height:40px;border:0;border-radius:10px;background:rgba(0,151,178,.10);font-size:21px;cursor:pointer;color:#087f95}.docio-header-logo{width:48px;height:42px;object-fit:contain;border-radius:7px}.docio-header-center{min-width:0;display:flex;flex-direction:column}.docio-header-center b{font-size:.94rem;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:48vw}.docio-header-center small{opacity:.62}.docio-header-brand{margin-left:auto;font-weight:800;color:#0797b2;letter-spacing:.04em}
    .docio-home-active .content{position:relative;background-image:linear-gradient(rgba(255,255,255,.68),rgba(255,255,255,.78)),url('media/docio-home-bg.jpg');background-size:cover;background-position:center;background-attachment:fixed;min-height:calc(100vh - 64px)}
    body.docio-home-active .content>.between,body.docio-home-active .content>.weekly-schedule-grid,body.docio-home-active .content>.card{position:relative;z-index:1}body.docio-home-active .schedule-day.card,body.docio-home-active .content>.card{background:rgba(255,255,255,.88);backdrop-filter:blur(4px)}body.docio-home-active .schedule-session{background:rgba(255,255,255,.84)}
    .docio-home-tools{display:flex;align-items:center;gap:10px;margin-bottom:12px}.docio-menu-btn{width:44px;height:44px;border:1px solid rgba(0,0,0,.12);border-radius:11px;background:rgba(255,255,255,.94);font-size:25px;line-height:1;cursor:pointer}.docio-today-date{margin-left:auto;padding:9px 13px;border-radius:10px;background:rgba(255,255,255,.9);font-weight:700}.docio-menu-panel{position:fixed;top:66px;left:12px;z-index:130;width:min(310px,calc(100vw - 24px));padding:10px;background:#fff;border-radius:14px;box-shadow:0 10px 35px rgba(0,0,0,.22);border:1px solid rgba(0,0,0,.08)}.docio-menu-panel[hidden]{display:none}.docio-menu-panel button{display:flex;width:100%;border:0;background:transparent;padding:12px;border-radius:9px;text-align:left;font-weight:650;cursor:pointer}.docio-menu-panel button:hover{background:rgba(0,151,178,.08)}
    .docio-setup-overlay{position:fixed;inset:0;z-index:9999;display:grid;place-items:center;padding:18px;background:linear-gradient(rgba(255,255,255,.80),rgba(255,255,255,.88)),url('media/docio-home-bg.jpg') center/cover}.docio-setup-card{width:min(440px,100%);padding:28px;border-radius:18px;background:rgba(255,255,255,.96);box-shadow:0 14px 48px rgba(0,0,0,.20);text-align:center}.docio-setup-logo{width:180px;max-height:155px;object-fit:contain;border-radius:10px;margin-bottom:8px}.docio-setup-card input{width:100%;box-sizing:border-box;margin:12px 0 16px;padding:13px;border:1px solid #cfd8dc;border-radius:9px;font-size:1rem}.docio-setup-card button{width:100%;padding:12px;border:0;border-radius:9px;background:#079db7;color:#fff;font-weight:750}
    @media(max-width:800px){body.docio-home-active .content{background-attachment:scroll;background-position:center top}.docio-header-brand{display:none}.docio-header-center b{max-width:55vw}.docio-today-date{font-size:.86rem}}
  `;
  document.head.appendChild(style);

  function centerName(){return (localStorage.getItem(CENTER_KEY)||'').trim()}
  function escText(s){const d=document.createElement('div');d.textContent=s||'';return d.innerHTML}
  function goHome(){if(typeof state!=='undefined'){state.page='today';state.groupId=null;render()}}
  function ensureHeader(){
    if(document.querySelector('.docio-global-header'))return;
    const h=document.createElement('header');h.className='docio-global-header';h.innerHTML=`<button class="docio-home-btn" aria-label="Ir al horario semanal" title="Inicio · Horario semanal">⌂</button><img class="docio-header-logo" src="media/docio-logo.jpg" alt="DOCIO"><div class="docio-header-center"><b id="docioCenterName"></b><small>DOCIO · Cuaderno Digital</small></div><div class="docio-header-brand">DOCIO</div>`;document.body.appendChild(h);h.querySelector('.docio-home-btn').onclick=goHome;
  }
  function formatToday(){return new Intl.DateTimeFormat('es-ES',{weekday:'long',day:'numeric',month:'long',year:'numeric'}).format(new Date()).replace(/^./,c=>c.toUpperCase())}
  function homeTools(){
    if(typeof state==='undefined'||state.page!=='today')return;
    const content=document.querySelector('.content');if(!content||content.querySelector('.docio-home-tools'))return;
    const box=document.createElement('div');box.className='docio-home-tools';box.innerHTML=`<button class="docio-menu-btn" aria-label="Abrir menú" title="Menú">☰</button><div class="docio-today-date">${escText(formatToday())}</div>`;content.prepend(box);
    const menu=document.createElement('div');menu.className='docio-menu-panel';menu.hidden=true;menu.innerHTML=`<button data-page="today">⌂ &nbsp; Inicio · Horario semanal</button><button data-page="groups">Grupos</button><button data-page="evaluation">Evaluación y pruebas</button><button data-page="more">Más · Currículo, rúbricas y ajustes</button><button id="docioChangeCenter">Cambiar centro educativo</button>`;document.body.appendChild(menu);
    box.querySelector('.docio-menu-btn').onclick=e=>{e.stopPropagation();menu.hidden=!menu.hidden};menu.querySelectorAll('[data-page]').forEach(b=>b.onclick=()=>{state.page=b.dataset.page;state.groupId=null;menu.remove();render()});menu.querySelector('#docioChangeCenter').onclick=()=>{menu.remove();showSetup(true)};setTimeout(()=>document.addEventListener('click',()=>{if(menu.isConnected)menu.hidden=true},{once:true}),0);
  }
  function showSetup(force=false){
    if(!force&&centerName())return;if(document.querySelector('.docio-setup-overlay'))return;
    const o=document.createElement('div');o.className='docio-setup-overlay';o.innerHTML=`<div class="docio-setup-card"><img src="media/docio-logo.jpg" class="docio-setup-logo" alt="DOCIO"><h2>Configurar centro educativo</h2><p>Introduce el nombre del centro. Aparecerá en todas las pantallas.</p><input id="docioCenterInput" autocomplete="organization" placeholder="Nombre del centro educativo" value="${escText(centerName())}"><button id="docioCenterSave">Continuar</button></div>`;document.body.appendChild(o);const input=o.querySelector('#docioCenterInput');input.focus();const save=()=>{const v=input.value.trim();if(!v){input.focus();return}localStorage.setItem(CENTER_KEY,v);o.remove();sync();goHome()};o.querySelector('#docioCenterSave').onclick=save;input.addEventListener('keydown',e=>{if(e.key==='Enter')save()});
  }
  function sync(){
    ensureHeader();const active=typeof state!=='undefined'&&state.page==='today';document.body.classList.toggle('docio-home-active',active);const n=document.querySelector('#docioCenterName');if(n)n.textContent=centerName()||'Centro educativo';homeTools();showSetup(false);
  }
  new MutationObserver(sync).observe(document.getElementById('app')||document.body,{childList:true,subtree:true});sync();
})();
