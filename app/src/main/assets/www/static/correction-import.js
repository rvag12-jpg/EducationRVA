'use strict';
(()=>{
const FMT='DOCIO_CORRECTION_V1';
/* La importación permanece disponible como API interna, pero no se muestra ningún botón flotante o global en las páginas. */
function removeLegacyButtons(){document.querySelector('#docioImportCorrectionBtn')?.remove();document.querySelectorAll('[data-docio-import-entry]').forEach(x=>x.remove())}
removeLegacyButtons();new MutationObserver(removeLegacyButtons).observe(document.body,{childList:true,subtree:true});
window.DOCIOCorrectionImport=window.DOCIOCorrectionImport||{format:FMT};
})();