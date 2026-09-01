'use strict';
(()=>{
const norm=s=>(s||'').replace(/\s+/g,' ').trim().toLowerCase();
function getDB(){try{const s=window.Android&&Android.secureLoad?Android.secureLoad():localStorage.getItem('aulaevidencia_db');return JSON.parse(s||'{}')}catch(e){return {}}}
function studentName(s){return s.name||[s.last_name,s.first_name].filter(Boolean).join(' ').trim()}
function findStudent(el){const id=el?.closest('[data-student-id]')?.dataset?.studentId;if(id)return (getDB().students||[]).find(s=>s.id===id)||null;const t=norm(el?.textContent);return (getDB().students||[]).find(s=>norm(studentName(s))===t)||null}
function pressExistingEditor(s,origin){const scopes=[origin?.closest('tr,li,.student-row,.card,[data-student-id]'),document].filter(Boolean);for(const scope of scopes){const controls=[...scope.querySelectorAll('button,a,[role="button"]')];const b=controls.find(x=>/editar|edit|lápiz|lapiz/i.test((x.textContent||'')+' '+(x.title||'')+' '+(x.getAttribute('aria-label')||'')));if(b&&b!==origin){b.click();return true}}return false}
function openStudent(s,origin){if(pressExistingEditor(s,origin))return;window.dispatchEvent(new CustomEvent('docio-edit-student',{detail:{studentId:s.id,student:s}}));}
function isStudentScreen(){const t=norm(document.body.innerText);return t.includes('alumn')&&t.includes('grupo')}
document.addEventListener('click',e=>{if(!isStudentScreen()||e.target.closest('button,a,input,select,textarea,label'))return;const el=e.target.closest('[data-student-id],.student-row,td,li');if(!el)return;const s=findStudent(el);if(!s)return;e.preventDefault();e.stopImmediatePropagation();openStudent(s,el)},true);
function decorate(){if(!isStudentScreen())return;document.querySelectorAll('[data-student-id],.student-row,td,li').forEach(el=>{const s=findStudent(el);if(s){el.style.cursor='pointer';el.title='Pulsar para editar alumno';el.setAttribute('role','button');el.setAttribute('tabindex','0')}})}
document.addEventListener('keydown',e=>{if((e.key==='Enter'||e.key===' ')&&e.target.matches('[data-student-id],.student-row,td,li')){const s=findStudent(e.target);if(s){e.preventDefault();openStudent(s,e.target)}}});
new MutationObserver(decorate).observe(document.body,{childList:true,subtree:true});setTimeout(decorate,100);
})();