'use strict';
(() => {
  const style=document.createElement('style');
  style.textContent=`
    body.docio-home-active .content{
      position:relative;
      background-image:linear-gradient(rgba(255,255,255,.76),rgba(255,255,255,.82)),url('media/docio-home-bg.jpg');
      background-size:cover;
      background-position:center;
      background-attachment:fixed;
      min-height:100vh;
    }
    body.docio-home-active .content>.between,
    body.docio-home-active .content>.weekly-schedule-grid,
    body.docio-home-active .content>.card{position:relative;z-index:1}
    body.docio-home-active .schedule-day.card,
    body.docio-home-active .content>.card{background:rgba(255,255,255,.88);backdrop-filter:blur(4px)}
    body.docio-home-active .schedule-session{background:rgba(255,255,255,.82)}
    @media(max-width:800px){body.docio-home-active .content{background-attachment:scroll;background-position:center top}}
  `;
  document.head.appendChild(style);
  function sync(){
    const active=typeof state!=='undefined' && state.page==='today';
    document.body.classList.toggle('docio-home-active',active);
  }
  new MutationObserver(sync).observe(document.getElementById('app')||document.body,{childList:true,subtree:true});
  sync();
})();
