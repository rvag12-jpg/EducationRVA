'use strict';
(()=>{
function getDB(){try{const s=window.Android&&Android.secureLoad?Android.secureLoad():localStorage.getItem('aulaevidencia_db');return JSON.parse(s||'{}')}catch(e){return {}}}
const norm=s=>(s||'').replace(/\s+/g,' ').trim().toLowerCase();
function studentName(s){return s.name||[s.last_name,s.first_name].filter(Boolean).join(' ').trim()}
function findStudent(text){const t=norm(text);return (getDB().students||[]).find(s=>{const n=norm(studentName(s));return n&&n.length>2&&(t===n||t.startsWith(n)||t.includes(n))})}
function clickEdit(s){const rows=[...document.querySelectorAll('tr,li,[data-student-id],.student-row,.card')];const row=rows.find(r=>r.dataset?.studentId===s.id||norm(r.textContent).includes(norm(studentName(s))));if(row){const b=[...row.querySelectorAll('button,a,[role="button"]')].find(x=>/editar|edit|lapiz|lápiz/i.test((x.textContent||'')+' '+(x.title||'')+' '+(x.getAttribute('aria-label')||'')));if(b){b.click();return}}
const bs=[...document.querySelectorAll('button,a,[role="button"]')];const b=bs.find(x=>/editar|edit/i.test(x.textContent||'')&&norm(x.textContent).includes(norm(studentName(s))));if(b){b.click();return}
window.dispatchEvent(new CustomEvent('docio-edit-student',{detail:{studentId:s.id}}))}
document.addEventListener('click',e=>{if(e.target.closest('button,a,input,select,textarea,label'))return;const el=e.target.closest('[data-student-id],.student-row,td,li');if(!el)return;const s=findStudent(el.textContent);if(!s)return;const page=norm(document.body.innerText);if(!page.includes('grupo')&&!page.includes('alumn'))return;e.preventDefault();e.stopPropagation();clickEdit(s)},true);
document.addEventListener('mouseover',e=>{const el=e.target.closest('[data-student-id],.student-row,td,li');if(!el)return;const s=findStudent(el.textContent);if(s){el.style.cursor='pointer';el.title='Editar alumno'}},true);
})();