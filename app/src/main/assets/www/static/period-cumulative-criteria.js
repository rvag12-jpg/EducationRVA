'use strict';
(() => {
  const previousApi = window.localApi;
  if (typeof previousApi !== 'function' || !window.AulaEvidenciaStore) return;
  const U = u => new URL(u, 'https://local.docio');
  const avg = a => a.length ? a.reduce((x,y)=>x+y,0)/a.length : null;

  function periodCutoff(db, gid, selectedId){
    const ps=(db.periods||[]).filter(p=>Number(p.group_id)===Number(gid));
    const idx=ps.findIndex(p=>Number(p.id)===Number(selectedId));
    return new Set((idx<0?ps:ps.slice(0,idx+1)).map(p=>Number(p.id)));
  }
  function evidencePeriod(db,e){
    const a=(db.assessments||[]).find(x=>Number(x.id)===Number(e.assessment_id));
    return Number(e.period_id ?? (a&&a.period_id));
  }
  function criterionCodes(db,e){
    const item=(db.items||[]).find(x=>Number(x.id)===Number(e.item_id));
    if(item&&Array.isArray(item.criterion_codes)) return item.criterion_codes;
    return (db.bindings||[]).filter(b=>Number(b.item_id)===Number(e.item_id)).map(b=>b.criterion_code||b.criterionCode).filter(Boolean);
  }
  function aggregate(vals,policy,n){
    if(!vals.length)return null;
    if(policy==='LAST')return vals[vals.length-1];
    if(policy==='MAX')return Math.max(...vals);
    if(policy==='AVERAGE_LAST_N')return avg(vals.slice(-Math.max(1,Number(n)||3)));
    return avg(vals);
  }
  function normalize(e){
    if(e.normalized_score!=null)return Number(e.normalized_score);
    const raw=Number(e.raw_score), max=Number(e.max_score);
    return Number.isFinite(raw)&&max>0 ? raw/max*10 : null;
  }
  function cumulativeRows(gid,periodId){
    const db=JSON.parse(window.AulaEvidenciaStore.exportJson());
    const allowed=periodCutoff(db,gid,periodId);
    const group=(db.groups||[]).find(g=>Number(g.id)===Number(gid));
    const currId=group&&(group.curriculum_id||group.curriculumId);
    const curricula=[...(window.DOCIO_CURRICULA||[]),...(db.customCurricula||[])];
    const curr=curricula.find(c=>c.id===currId);
    const criteria=(curr&&curr.criteria)||[];
    const enroll=(db.enrollments||[]).filter(x=>Number(x.group_id)===Number(gid)&&x.active!==false);
    const policy=(db.settings&&db.settings.defaultAggregation)||'AVERAGE_ALL';
    const lastN=(db.settings&&db.settings.aggregationLastN)||3;
    return enroll.map(en=>{
      const ev=(db.evidence||[]).filter(e=>Number(e.enrollment_id)===Number(en.id)&&allowed.has(evidencePeriod(db,e))&&e.status!=='VOID');
      const cr=criteria.map(c=>{
        const vals=ev.filter(e=>criterionCodes(db,e).includes(c.code)).map(normalize).filter(Number.isFinite);
        return {code:c.code, competence:c.competence, value:aggregate(vals,policy,lastN), evidenceCount:vals.length};
      });
      const values=cr.map(c=>c.value).filter(v=>v!=null&&Number.isFinite(Number(v)));
      return {enrollmentId:en.id, criteria:cr, criterionFinal:avg(values), technicalIndicator:avg(values)};
    });
  }

  window.localApi=async function(url,opts={}){
    const u=U(url), method=(opts.method||'GET').toUpperCase();
    const m=u.pathname.match(/^\/api\/groups\/(\d+)\/gradebook$/);
    if(method==='GET'&&m&&u.searchParams.get('view')==='criteria'&&u.searchParams.get('period_id')){
      const result=await previousApi(url,opts);
      const rows=cumulativeRows(Number(m[1]),Number(u.searchParams.get('period_id')));
      const by=new Map(rows.map(r=>[Number(r.enrollmentId),r]));
      if(result&&Array.isArray(result.rows)) result.rows=result.rows.map(r=>({...r,...(by.get(Number(r.enrollmentId))||{})}));
      result.periodCumulative=true;
      return result;
    }
    return previousApi(url,opts);
  };

  function enhance(){
    const app=document.getElementById('app'); if(!app)return;
    const text=(app.innerText||'').toLowerCase(); if(!text.includes('cuaderno'))return;
    app.querySelectorAll('table').forEach(t=>{
      const hs=[...t.querySelectorAll('thead th')]; if(hs.length<2)return;
      const first=(hs[0].textContent||'').toLowerCase(); if(!/alumn|estudiant/.test(first))return;
      const crit=hs.some(h=>/criter|ce\d/i.test(h.textContent||'')); if(!crit)return;
      if(t.dataset.docioCumulative==='1')return;
      t.dataset.docioCumulative='1';
      const h=document.createElement('th');h.textContent='Media criterial';h.title='Media acumulada de los criterios evaluados hasta la evaluación seleccionada';hs[0].after(h);
      [...t.querySelectorAll('tbody tr')].forEach(tr=>{
        const cells=[...tr.children]; if(!cells.length)return;
        let vals=[];
        for(let i=1;i<cells.length;i++){
          const s=(cells[i].querySelector('input')?.value||cells[i].textContent||'').trim().replace(',','.');
          const v=Number(s); if(s!==''&&Number.isFinite(v)&&v>=0&&v<=10) vals.push(v);
        }
        const td=document.createElement('td'); const v=avg(vals);td.textContent=v==null?'—':v.toFixed(2);td.className='docio-criterion-mean';cells[0].after(td);
      });
    });
  }
  new MutationObserver(()=>enhance()).observe(document.getElementById('app')||document.body,{childList:true,subtree:true});
  enhance();
})();
