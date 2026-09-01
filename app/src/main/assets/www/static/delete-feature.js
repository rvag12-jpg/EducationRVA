'use strict';
(() => {
  const baseApi=window.localApi;
  if(typeof baseApi!=='function'||!window.AulaEvidenciaStore)return;
  let activeGroupId=null,scanTimer=null,scanning=false;
  const exportDb=()=>JSON.parse(window.AulaEvidenciaStore.exportJson());
  const saveDb=db=>window.AulaEvidenciaStore.importJson(JSON.stringify(db));
  const ok=(data={})=>({...data,deleted:true});

  function removeAssessment(db,assessmentId){
    const aid=Number(assessmentId),a=(db.assessments||[]).find(x=>Number(x.id)===aid);
    if(!a)throw new Error('Prueba no encontrada');
    const itemIds=new Set((db.items||[]).filter(i=>Number(i.assessment_id)===aid).map(i=>i.id));
    const evidenceIds=new Set((db.evidence||[]).filter(e=>Number(e.assessment_id)===aid||itemIds.has(e.assessment_item_id)).map(e=>e.id));
    db.revisions=(db.revisions||[]).filter(r=>!evidenceIds.has(r.evidence_id));
    db.evidence=(db.evidence||[]).filter(e=>Number(e.assessment_id)!==aid&&!itemIds.has(e.assessment_item_id));
    db.bindings=(db.bindings||[]).filter(b=>Number(b.assessment_id)!==aid&&!itemIds.has(b.item_id));
    db.items=(db.items||[]).filter(i=>Number(i.assessment_id)!==aid);
    db.assessments=(db.assessments||[]).filter(x=>Number(x.id)!==aid);
    return {assessmentId:aid,title:a.title||a.name||'Prueba'};
  }
  function removeEnrollment(db,enrollmentId){const eid=Number(enrollmentId),enrollment=db.enrollments.find(e=>e.id===eid);if(!enrollment)throw new Error('Matrícula no encontrada');const studentId=enrollment.student_id,evidenceIds=new Set(db.evidence.filter(e=>e.enrollment_id===eid).map(e=>e.id));db.revisions=db.revisions.filter(r=>!evidenceIds.has(r.evidence_id));db.evidence=db.evidence.filter(e=>e.enrollment_id!==eid);db.attendance=db.attendance.filter(a=>a.enrollment_id!==eid);db.observations=db.observations.filter(o=>o.enrollment_id!==eid);db.enrollments=db.enrollments.filter(e=>e.id!==eid);(db.snapshots||[]).forEach(s=>{if(Array.isArray(s.results))s.results=s.results.filter(r=>r.enrollment_id!==eid)});if(!db.enrollments.some(e=>e.student_id===studentId))db.students=db.students.filter(s=>s.id!==studentId);return {enrollmentId:eid,studentId};}
  function removeGroup(db,groupId){const gid=Number(groupId),group=db.groups.find(g=>g.id===gid&&!g.archived);if(!group)throw new Error('Grupo no encontrado');const enrollmentIds=new Set(db.enrollments.filter(e=>e.group_id===gid).map(e=>e.id)),studentIds=new Set(db.enrollments.filter(e=>e.group_id===gid).map(e=>e.student_id)),assessmentIds=new Set(db.assessments.filter(a=>a.group_id===gid).map(a=>a.id)),itemIds=new Set(db.items.filter(i=>assessmentIds.has(i.assessment_id)).map(i=>i.id)),evidenceIds=new Set(db.evidence.filter(e=>enrollmentIds.has(e.enrollment_id)||itemIds.has(e.assessment_item_id)).map(e=>e.id));db.revisions=db.revisions.filter(r=>!evidenceIds.has(r.evidence_id));db.evidence=db.evidence.filter(e=>!enrollmentIds.has(e.enrollment_id)&&!itemIds.has(e.assessment_item_id));db.bindings=db.bindings.filter(b=>!itemIds.has(b.item_id));db.items=db.items.filter(i=>!itemIds.has(i.id));db.assessments=db.assessments.filter(a=>a.group_id!==gid);db.periods=db.periods.filter(p=>p.group_id!==gid);db.attendance=db.attendance.filter(a=>!enrollmentIds.has(a.enrollment_id));db.observations=db.observations.filter(o=>!enrollmentIds.has(o.enrollment_id));db.snapshots=(db.snapshots||[]).filter(s=>s.group_id!==gid);db.enrollments=db.enrollments.filter(e=>e.group_id!==gid);studentIds.forEach(sid=>{if(!db.enrollments.some(e=>e.student_id===sid))db.students=db.students.filter(s=>s.id!==sid)});db.groups=db.groups.filter(g=>g.id!==gid);return {groupId:gid,name:group.name};}

  window.localApi=async function(url,opts={}){
    const method=(opts.method||'GET').toUpperCase(),path=new URL(url,'https://local.aulaevidencia').pathname;let m;
    if(method==='DELETE'&&(m=path.match(/^\/api\/assessments\/(\d+)$/))){const db=exportDb(),out=removeAssessment(db,m[1]);saveDb(db);return ok(out)}
    if(method==='DELETE'&&(m=path.match(/^\/api\/enrollments\/(\d+)$/))){const db=exportDb(),out=removeEnrollment(db,m[1]);saveDb(db);return ok(out)}
    if(method==='DELETE'&&(m=path.match(/^\/api\/groups\/(\d+)$/))){const db=exportDb(),out=removeGroup(db,m[1]);saveDb(db);if(activeGroupId===out.groupId)activeGroupId=null;return ok(out)}
    const result=await baseApi(url,opts);
    if(method==='GET'&&(m=path.match(/^\/api\/groups\/(\d+)$/)))activeGroupId=Number(m[1]);
    if(method==='GET'&&(m=path.match(/^\/api\/groups\/(\d+)\/students$/)))activeGroupId=Number(m[1]);
    return result;
  };

  function toast(message){let t=document.querySelector('.toast');if(!t){t=document.createElement('div');t.className='toast';document.body.appendChild(t)}t.textContent=message;t.classList.add('show');setTimeout(()=>t.classList.remove('show'),2300)}
  async function deleteAssessment(a){if(!a)return;if(!confirm(`¿Eliminar definitivamente la prueba «${a.title||a.name||'Prueba'}»?\n\nSe borrarán sus preguntas, evidencias y calificaciones asociadas.`))return;const word=prompt('Para confirmar escribe ELIMINAR');if((word||'').trim().toUpperCase()!=='ELIMINAR')return toast('Borrado cancelado');try{await window.localApi(`/api/assessments/${a.id}`,{method:'DELETE'});toast('Prueba eliminada');setTimeout(()=>location.reload(),180)}catch(e){toast(e.message||'No se pudo eliminar la prueba')}}
  async function deleteStudent(button){const eid=Number(button.dataset.observe),name=button.dataset.name||'este alumno';if(!confirm(`¿Eliminar definitivamente a ${name} de este grupo?\n\nSe borrarán sus evidencias, asistencia y anotaciones asociadas a esta matrícula.`))return;try{await window.localApi(`/api/enrollments/${eid}`,{method:'DELETE'});toast('Alumno eliminado');document.querySelector('[data-gtab="students"]')?.click()}catch(e){toast(e.message||'No se pudo eliminar el alumno')}}
  async function deleteGroup(){if(!activeGroupId)return toast('No se pudo identificar el grupo');const title=document.querySelector('.group-heading .page-title')?.textContent?.trim()||'este grupo';if(!confirm(`¿Eliminar definitivamente ${title}?\n\nSe borrarán sus pruebas, evidencias, alumnado exclusivo, asistencia, observaciones y cierres de evaluación.`))return;const word=prompt('Para confirmar el borrado completo escribe ELIMINAR');if((word||'').trim().toUpperCase()!=='ELIMINAR')return toast('Borrado cancelado');try{const gid=activeGroupId;await window.localApi(`/api/groups/${gid}`,{method:'DELETE'});toast('Grupo eliminado');document.querySelector('#backGroups')?.click()}catch(e){toast(e.message||'No se pudo eliminar el grupo')}}

  function assessmentFromRow(row,db){
    const id=row.dataset.assessmentId||row.querySelector('[data-assessment-id]')?.dataset.assessmentId;
    if(id)return (db.assessments||[]).find(a=>String(a.id)===String(id))||null;
    const title=row.querySelector('b')?.textContent?.replace(/^\s*/,'').trim();
    if(!title)return null;
    const candidates=(db.assessments||[]).filter(a=>(!activeGroupId||Number(a.group_id)===Number(activeGroupId))&&(a.title||a.name||'').trim()===title);
    return candidates.length===1?candidates[0]:null;
  }
  function scan(){
    if(scanning)return;scanning=true;
    try{
      const db=exportDb();
      const heading=document.querySelector('.group-heading');
      if(heading&&!document.querySelector('#deleteGroupFeature')){const right=heading.lastElementChild,b=document.createElement('button');b.id='deleteGroupFeature';b.className='btn danger';b.textContent='Eliminar grupo';b.onclick=deleteGroup;if(right&&right.classList.contains('row'))right.appendChild(b);else heading.appendChild(b)}
      document.querySelectorAll('[data-observe]').forEach(observe=>{const row=observe.closest('.list-row');if(!row||row.querySelector('[data-delete-enrollment]'))return;const b=document.createElement('button');b.className='btn danger';b.textContent='Eliminar';b.dataset.deleteEnrollment=observe.dataset.observe;b.onclick=()=>deleteStudent(observe);const action=observe.parentElement;if(action&&action.classList.contains('row'))action.appendChild(b);else{const wrap=document.createElement('div');wrap.className='row';observe.replaceWith(wrap);wrap.appendChild(observe);wrap.appendChild(b)}});
      document.querySelectorAll('.assessment-row').forEach(row=>{if(row.dataset.docioDeleteReady==='1')return;const a=assessmentFromRow(row,db);row.dataset.docioDeleteReady='1';if(!a)return;const b=document.createElement('button');b.type='button';b.className='btn danger docio-delete-assessment';b.textContent='Eliminar prueba';b.dataset.assessmentId=a.id;b.onclick=e=>{e.preventDefault();e.stopPropagation();deleteAssessment(a)};const actions=row.querySelector('.row');(actions||row).appendChild(b)});
    }finally{scanning=false}
  }
  function scheduleScan(){if(scanTimer)return;scanTimer=setTimeout(()=>{scanTimer=null;scan()},120)}
  const root=document.getElementById('app')||document.body;
  new MutationObserver(mutations=>{if(mutations.some(m=>[...m.addedNodes].some(n=>n.nodeType===1&&!n.classList?.contains('docio-delete-assessment'))))scheduleScan()}).observe(root,{childList:true,subtree:true});
  scheduleScan();
})();
