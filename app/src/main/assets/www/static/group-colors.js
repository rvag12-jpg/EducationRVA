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

  // Misma paleta que las pruebas: 32 tonos × 8 saturaciones × 4 luminosidades = 1024 colores.
  const PALETTE=[];
  for(let hi=0;hi<32;hi++){
    const h=hi*(360/32);
    for(let si=0;si<8;si++){
      const s=30+si*9;
      for(let li=0;li<4;li++) PALETTE.push(hslToHex(h,s,34+li*15));
    }
  }
  const DEFAULT_GROUP_COLOR='#385F8A';
  const validHex=c=>typeof c==='string'&&/^#[0-9A-Fa-f]{6}$/.test(c);

  function groupModal(){
    const create=document.querySelector('#create');
    const name=document.querySelector('#gName');
    if(!create||!name) return null;
    return create.closest('.modal, .modal-card, [role="dialog"]') || create.parentElement?.parentElement;
  }

  function selectedColor(){
    const modal=groupModal();
    const c=modal?.dataset?.groupColor;
    return validHex(c)?c.toUpperCase():DEFAULT_GROUP_COLOR;
  }

  function paletteMarkup(selected){
    const normalized=validHex(selected)?selected.toUpperCase():DEFAULT_GROUP_COLOR;
    return `<div class="field ae-group-color-field"><label>Color del grupo · 1024 colores</label><div class="ae-group-selected-color"><span class="ae-group-selected-preview" style="background:${normalized}"></span><code>${normalized}</code></div><div class="ae-group-color-palette" role="group" aria-label="Paleta de 1024 colores del grupo">${PALETTE.map(c=>`<button type="button" class="ae-group-color-swatch ${c===normalized?'selected':''}" data-ae-group-color="${c}" style="background:${c}" title="${c}" aria-label="Elegir color ${c}"></button>`).join('')}</div><div class="muted">El color elegido identificará al grupo en el horario semanal y en las vistas donde se muestre el grupo.</div></div>`;
  }

  function injectPalette(){
    const modal=groupModal();
    if(!modal||modal.querySelector('.ae-group-color-field')) return;
    modal.dataset.groupColor=DEFAULT_GROUP_COLOR;
    const anchor=modal.querySelector('#gCourse')?.closest('.field') || modal.querySelector('#gSubject')?.closest('.field');
    if(anchor) anchor.insertAdjacentHTML('afterend',paletteMarkup(DEFAULT_GROUP_COLOR));
    else modal.querySelector('#create')?.parentElement?.insertAdjacentHTML('beforebegin',paletteMarkup(DEFAULT_GROUP_COLOR));
    modal.querySelectorAll('.ae-group-color-swatch').forEach(btn=>btn.addEventListener('click',()=>{
      const color=btn.dataset.aeGroupColor;
      modal.dataset.groupColor=color;
      modal.querySelectorAll('.ae-group-color-swatch').forEach(x=>x.classList.toggle('selected',x===btn));
      const preview=modal.querySelector('.ae-group-selected-preview'); if(preview) preview.style.background=color;
      const code=modal.querySelector('.ae-group-selected-color code'); if(code) code.textContent=color;
    }));
  }

  window.localApi = async function(url,opts={}){
    const method=(opts.method||'GET').toUpperCase();
    const u=new URL(url,'https://local.aulaevidencia');
    if(method==='POST' && u.pathname==='/api/groups'){
      let body={};
      try{ body=typeof opts.body==='string'?JSON.parse(opts.body):(opts.body||{}); }catch(_){ body={}; }
      body.color=selectedColor();
      return baseApi(url,{...opts,body:JSON.stringify(body)});
    }
    return baseApi(url,opts);
  };

  const style=document.createElement('style');
  style.textContent=`
    .ae-group-selected-color{display:flex;align-items:center;gap:8px;margin:7px 0}
    .ae-group-selected-preview{width:28px;height:28px;border-radius:7px;border:1px solid rgba(0,0,0,.22)}
    .ae-group-selected-color code{font-size:12px}
    .ae-group-color-palette{display:grid;grid-template-columns:repeat(16,22px);gap:4px;max-height:220px;overflow:auto;padding:8px;margin:7px 0 6px;border:1px solid rgba(0,0,0,.14);border-radius:10px;background:#fff}
    .ae-group-color-swatch{width:22px;height:22px;border:1px solid rgba(0,0,0,.18);border-radius:4px;padding:0;box-shadow:none;min-width:22px}
    .ae-group-color-swatch.selected{outline:3px solid #173b67;outline-offset:1px;z-index:1}
    @media (max-width:600px){.ae-group-color-palette{grid-template-columns:repeat(12,22px);max-height:200px}}
  `;
  document.head.appendChild(style);
  new MutationObserver(injectPalette).observe(document.getElementById('app')||document.body,{childList:true,subtree:true});
  injectPalette();
})();
