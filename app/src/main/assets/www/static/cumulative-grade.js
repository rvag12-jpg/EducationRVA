'use strict';
(() => {
  const baseApi = window.localApi;
  if (typeof baseApi !== 'function') return;

  const asUrl = (url) => new URL(url, 'https://local.aulaevidencia');
  const mean = values => values.length ? values.reduce((a,b)=>a+b,0)/values.length : null;

  function enrichStudent(row){
    const comps = (row.competences || []).filter(c => c.value !== null && c.value !== undefined);
    const competenceFinal = mean(comps.map(c => Number(c.value)));
    return {...row, competenceFinal, criterionFinal: row.technicalIndicator};
  }

  async function getCumulativeCriteria(gid){
    const rows = await baseApi(`/api/groups/${gid}/criteria-results`);
    return (rows || []).map(enrichStudent);
  }

  async function rewriteReport(url, opts){
    const u = asUrl(url);
    const m = u.pathname.match(/^\/api\/groups\/(\d+)\/report$/);
    const report = await baseApi(url, opts);
    if (!m) return report;
    const cumulative = await getCumulativeCriteria(Number(m[1]));
    const byEnrollment = new Map(cumulative.map(r => [r.enrollmentId, r]));
    report.students = (report.students || []).map(s => {
      const c = byEnrollment.get(s.enrollmentId);
      return c ? {...s,
        technicalIndicator:c.criterionFinal,
        criterionFinal:c.criterionFinal,
        competenceFinal:c.competenceFinal,
        criteria:c.criteria,
        competences:c.competences
      } : s;
    });
    return report;
  }

  async function closeWithCumulativeSnapshot(url, opts){
    const u = asUrl(url);
    const m = u.pathname.match(/^\/api\/periods\/(\d+)\/close$/);
    if (!m || !window.AulaEvidenciaStore) return baseApi(url, opts);
    const periodId = Number(m[1]);
    const dbBefore = JSON.parse(window.AulaEvidenciaStore.exportJson());
    const period = (dbBefore.periods || []).find(p => p.id === periodId);
    if (!period) return baseApi(url, opts);
    const cumulative = await getCumulativeCriteria(period.group_id);
    const byEnrollment = new Map(cumulative.map(r => [r.enrollmentId, r]));
    const snapshot = await baseApi(url, opts);
    const db = JSON.parse(window.AulaEvidenciaStore.exportJson());
    const stored = (db.snapshots || []).find(s => s.id === snapshot.id);
    if (stored) {
      stored.results = (stored.results || []).map(r => {
        const c = byEnrollment.get(r.enrollment_id);
        return c ? {...r,
          technical_indicator:c.criterionFinal,
          criterion_final:c.criterionFinal,
          competence_final:c.competenceFinal,
          criteria:(c.criteria || []).map(x => ({code:x.code,value:x.value,evidenceCount:x.evidenceCount})),
          competences:c.competences || []
        } : r;
      });
      window.AulaEvidenciaStore.importJson(JSON.stringify(db));
      snapshot.results = stored.results;
    }
    return snapshot;
  }

  window.localApi = async function(url, opts={}){
    const method = (opts.method || 'GET').toUpperCase();
    const u = asUrl(url);
    const path = u.pathname;

    if (method === 'GET' && /^\/api\/groups\/\d+\/criteria-results$/.test(path)) {
      u.searchParams.delete('period_id');
      const rows = await baseApi(u.pathname + (u.search ? u.search : ''), opts);
      return (rows || []).map(enrichStudent);
    }

    if (method === 'GET' && /^\/api\/groups\/\d+\/gradebook$/.test(path) && u.searchParams.get('view') === 'criteria') {
      u.searchParams.delete('period_id');
      const result = await baseApi(u.pathname + '?' + u.searchParams.toString(), opts);
      if (result && Array.isArray(result.rows)) result.rows = result.rows.map(enrichStudent);
      return result;
    }

    if (method === 'GET' && /^\/api\/groups\/\d+\/analysis$/.test(path)) {
      u.searchParams.delete('period_id');
      return baseApi(u.pathname + (u.search ? u.search : ''), opts);
    }

    if (method === 'GET' && /^\/api\/groups\/\d+\/report$/.test(path)) return rewriteReport(url, opts);
    if (method === 'POST' && /^\/api\/periods\/\d+\/close$/.test(path)) return closeWithCumulativeSnapshot(url, opts);
    return baseApi(url, opts);
  };

  function replaceTextNodes(root){
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    const replacements = [
      ['Indicador técnico medio', 'Nota criterial acumulada media'],
      ['Media de resultados criteriales disponibles', 'Media de criterios evaluados hasta la fecha'],
      ['Indicador técnico', 'Nota criterial acumulada'],
      ['El indicador técnico es informativo.', 'La nota criterial acumulada es la media de todos los criterios evaluados hasta la fecha. La nota competencial acumulada es la media de las competencias evaluadas, calculadas a partir de sus criterios asociados.'],
      ['Resultados por criterio calculados como media de evidencias disponibles.', 'Resultados acumulados por criterio con todas las evidencias registradas hasta la fecha, aunque pertenezcan a evaluaciones distintas.']
    ];
    let node;
    while ((node = walker.nextNode())) {
      let text = node.nodeValue;
      let changed = false;
      for (const [from,to] of replacements) if (text.includes(from)) { text = text.replaceAll(from,to); changed = true; }
      if (changed) node.nodeValue = text;
    }
  }

  const target = document.getElementById('app') || document.body;
  const observer = new MutationObserver(() => replaceTextNodes(target));
  observer.observe(target, {childList:true, subtree:true});
  replaceTextNodes(target);
})();
