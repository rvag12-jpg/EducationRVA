'use strict';
(() => {
  const baseApi = window.localApi;
  if (typeof baseApi !== 'function' || !window.AulaEvidenciaStore) return;

  let activeGroupId = null;

  function exportDb(){ return JSON.parse(window.AulaEvidenciaStore.exportJson()); }
  function saveDb(db){ window.AulaEvidenciaStore.importJson(JSON.stringify(db)); }
  function ok(data={}){ return {...data, deleted:true}; }

  function removeEnrollment(db,enrollmentId){
    const eid=Number(enrollmentId), enrollment=db.enrollments.find(e=>e.id===eid);
    if(!enrollment) throw new Error('Matrícula no encontrada');
    const studentId=enrollment.student_id;
    const evidenceIds=new Set(db.evidence.filter(e=>e.enrollment_id===eid).map(e=>e.id));
    db.revisions=db.revisions.filter(r=>!evidenceIds.has(r.evidence_id));
    db.evidence=db.evidence.filter(e=>e.enrollment_id!==eid);
    db.attendance=db.attendance.filter(a=>a.enrollment_id!==eid);
    db.observations=db.observations.filter(o=>o.enrollment_id!==eid);
    db.enrollments=db.enrollments.filter(e=>e.id!==eid);
    (db.snapshots||[]).forEach(s=>{
      if(Array.isArray(s.results)) s.results=s.results.filter(r=>r.enrollment_id!==eid);
    });
    if(!db.enrollments.some(e=>e.student_id===studentId)) db.students=db.students.filter(s=>s.id!==studentId);
    return {enrollmentId:eid,studentId};
  }

  function removeGroup(db,groupId){
    const gid=Number(groupId), group=db.groups.find(g=>g.id===gid&&!g.archived);
    if(!group) throw new Error('Grupo no encontrado');
    const enrollmentIds=new Set(db.enrollments.filter(e=>e.group_id===gid).map(e=>e.id));
    const studentIds=new Set(db.enrollments.filter(e=>e.group_id===gid).map(e=>e.student_id));
    const assessmentIds=new Set(db.assessments.filter(a=>a.group_id===gid).map(a=>a.id));
    const itemIds=new Set(db.items.filter(i=>assessmentIds.has(i.assessment_id)).map(i=>i.id));
    const evidenceIds=new Set(db.evidence.filter(e=>enrollmentIds.has(e.enrollment_id)||itemIds.has(e.assessment_item_id)).map(e=>e.id));

    db.revisions=db.revisions.filter(r=>!evidenceIds.has(r.evidence_id));
    db.evidence=db.evidence.filter(e=>!enrollmentIds.has(e.enrollment_id)&&!itemIds.has(e.assessment_item_id));
    db.bindings=db.bindings.filter(b=>!itemIds.has(b.item_id));
    db.items=db.items.filter(i=>!itemIds.has(i.id));
    db.assessments=db.assessments.filter(a=>a.group_id!==gid);
    db.periods=db.periods.filter(p=>p.group_id!==gid);
    db.attendance=db.attendance.filter(a=>!enrollmentIds.has(a.enrollment_id));
    db.observations=db.observations.filter(o=>!enrollmentIds.has(o.enrollment_id));
    db.snapshots=(db.snapshots||[]).filter(s=>s.group_id!==gid);
    db.enrollments=db.enrollments.filter(e=>e.group_id!==gid);
    studentIds.forEach(sid=>{
      if(!db.enrollments.some(e=>e.student_id===sid)) db.students=db.students.filter(s=>s.id!==sid);
    });
    db.groups=db.groups.filter(g=>g.id!==gid);
    return {groupId:gid,name:group.name};
  }

  window.localApi = async function(url,opts={}){
    const method=(opts.method||'GET').toUpperCase();
    const path=new URL(url,'https://local.aulaevidencia').pathname;
    let m;
    if(method==='DELETE' && (m=path.match(/^\/api\/enrollments\/(\d+)$/))){
      const db=exportDb(), out=removeEnrollment(db,Number(m[1])); saveDb(db); return ok(out);
    }
    if(method==='DELETE' && (m=path.match(/^\/api\/groups\/(\d+)$/))){
      const db=exportDb(), out=removeGroup(db,Number(m[1])); saveDb(db); if(activeGroupId===out.groupId)activeGroupId=null; return ok(out);
    }
    const result=await baseApi(url,opts);
    if(method==='GET' && (m=path.match(/^\/api\/groups\/(\d+)$/))) activeGroupId=Number(m[1]);
    if(method==='GET' && (m=path.match(/^\/api\/groups\/(\d+)\/students$/))) activeGroupId=Number(m[1]);
    return result;
  };

  function toast(message){
    let t=document.querySelector('.toast');
    if(!t){t=document.createElement('div');t.className='toast';document.body.appendChild(t);}
    t.textContent=message;t.classList.add('show');setTimeout(()=>t.classList.remove('show'),2300);
  }

  async function deleteStudent(button){
    const eid=Number(button.dataset.observe), name=button.dataset.name||'este alumno';
    if(!confirm(`¿Eliminar definitivamente a ${name} de este grupo?\n\nSe borrarán sus evidencias, asistencia y anotaciones asociadas a esta matrícula.`)) return;
    try{
      await window.localApi(`/api/enrollments/${eid}`,{method:'DELETE'});
      toast('Alumno eliminado');
      const tab=document.querySelector('[data-gtab="students"]');
      if(tab) tab.click();
    }catch(e){ toast(e.message||'No se pudo eliminar el alumno'); }
  }

  async function deleteGroup(){
    if(!activeGroupId) return toast('No se pudo identificar el grupo');
    const title=document.querySelector('.group-heading .page-title')?.textContent?.trim()||'este grupo';
    if(!confirm(`¿Eliminar definitivamente ${title}?\n\nSe borrarán sus pruebas, evidencias, alumnado exclusivo, asistencia, observaciones y cierres de evaluación.`)) return;
    const word=prompt('Para confirmar el borrado completo escribe ELIMINAR');
    if((word||'').trim().toUpperCase()!=='ELIMINAR') return toast('Borrado cancelado');
    try{
      const gid=activeGroupId;
      await window.localApi(`/api/groups/${gid}`,{method:'DELETE'});
      toast('Grupo eliminado');
      const back=document.querySelector('#backGroups');
      if(back) back.click();
    }catch(e){ toast(e.message||'No se pudo eliminar el grupo'); }
  }

  function installUi(){
    const heading=document.querySelector('.group-heading');
    if(heading && !document.querySelector('#deleteGroupFeature')){
      const right=heading.lastElementChild;
      const b=document.createElement('button');
      b.id='deleteGroupFeature'; b.className='btn danger'; b.textContent='Eliminar grupo'; b.onclick=deleteGroup;
      if(right && right.classList.contains('row')) right.appendChild(b); else heading.appendChild(b);
    }
    document.querySelectorAll('[data-observe]').forEach(observe=>{
      const row=observe.closest('.list-row');
      if(!row || row.querySelector('[data-delete-enrollment]')) return;
      const b=document.createElement('button');
      b.className='btn danger'; b.textContent='Eliminar'; b.dataset.deleteEnrollment=observe.dataset.observe;
      b.onclick=()=>deleteStudent(observe);
      const action=observe.parentElement;
      if(action && action.classList.contains('row')) action.appendChild(b);
      else { const wrap=document.createElement('div');wrap.className='row';observe.replaceWith(wrap);wrap.appendChild(observe);wrap.appendChild(b); }
    });
  }

  const observer=new MutationObserver(()=>installUi());
  observer.observe(document.getElementById('app')||document.body,{childList:true,subtree:true});
  installUi();
})();
