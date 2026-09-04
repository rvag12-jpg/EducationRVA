package es.iesvirgendelacaridad.corrector;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class ScoreEngine {
    private ScoreEngine() {}

    public static ArrayList<String> validateFmc(JSONObject fmc, double configuredTotal) throws JSONException {
        ArrayList<String> issues = new ArrayList<>();
        JSONArray qs = fmc.optJSONArray("questions");
        if (qs == null || qs.length() == 0) throw new JSONException("La FMC no contiene questions.");
        Set<String> qids = new HashSet<>(); double total = 0; boolean allKnown = true;
        for (int i=0;i<qs.length();i++) {
            JSONObject q=qs.getJSONObject(i); String id=q.optString("id","").trim();
            if(id.isEmpty()||!qids.add(id))throw new JSONException("ID vacío o duplicado: "+id);
            JSONArray m=q.optJSONArray("evidence_matrix"); if(m==null)throw new JSONException("Falta evidence_matrix en "+id);
            Set<String> codes=new HashSet<>(); double sum=0;
            for(int j=0;j<m.length();j++){
                JSONObject e=m.getJSONObject(j);String c=e.optString("code","").trim();
                if(c.isEmpty()||!codes.add(c))throw new JSONException("Evidencia vacía/duplicada en "+id);
                double p=e.optDouble("points",Double.NaN);if(!Double.isFinite(p)||p<0)throw new JSONException("Puntos inválidos en "+id+"/"+c);sum+=p;
                if(e.optBoolean("partial_allowed",false)){double pp=e.optDouble("partial_points",Double.NaN);if(!Double.isFinite(pp)||pp<0||pp>p)throw new JSONException("partial_points inválido en "+id+"/"+c);}
            }
            if(q.isNull("max_points"))allKnown=false;else{double max=q.optDouble("max_points",Double.NaN);if(!Double.isFinite(max)||max<0)throw new JSONException("max_points inválido en "+id);total+=max;if(Math.abs(sum-max)>.011)issues.add(id+": evidencias suman "+round(sum)+" y max_points="+round(max));}
            JSONArray c=q.optJSONArray("criteria");if(c!=null&&c.length()>2)issues.add(id+": más de dos criterios principales.");
        }
        if(allKnown&&Math.abs(total-configuredTotal)>.011)issues.add("Suma de cuestiones="+round(total)+" y total configurado="+round(configuredTotal));
        return issues;
    }

    public static JSONObject recompute(JSONObject fmc, JSONObject model, String studentCode, String presentationMode) throws JSONException {
        JSONArray fqs=fmc.getJSONArray("questions"),mqs=model.optJSONArray("question_results");if(mqs==null)mqs=new JSONArray();
        Map<String,JSONObject> byId=new HashMap<>();for(int i=0;i<mqs.length();i++){JSONObject q=mqs.optJSONObject(i);if(q!=null)byId.put(q.optString("id",""),q);}
        JSONArray outQs=new JSONArray(),blocks=new JSONArray();copy(model.optJSONArray("blocking_issues"),blocks);copy(fmc.optJSONArray("blocking_issues"),blocks);
        boolean nullMax=false,doubt=false;double totalMax=0,totalAward=0;
        for(int i=0;i<fqs.length();i++){
            JSONObject fq=fqs.getJSONObject(i);String id=fq.optString("id","Q"+(i+1));JSONObject mq=byId.get(id);if(mq==null){mq=new JSONObject();blocks.put("ChatGPT omitió "+id);}
            JSONObject out=new JSONObject().put("id",id).put("question",fq.optString("question",id)).put("item_id",fq.opt("item_id")==null?JSONObject.NULL:fq.opt("item_id"));
            out.put("legibility",mq.optString("legibility","mixed")).put("student_response_summary",mq.optString("student_response_summary","")).put("errors",mq.optJSONArray("errors")==null?new JSONArray():mq.optJSONArray("errors"));
            out.put("carried_error_treatment",mq.optString("carried_error_treatment","")).put("criteria",fq.optJSONArray("criteria")==null?new JSONArray():fq.optJSONArray("criteria")).put("justification",mq.optString("justification","")).put("material_doubt",mq.optBoolean("material_doubt",false)).put("comment",mq.optString("comment",mq.optString("justification","")));
            double conf=mq.optDouble("confidence",.8);out.put("confidence",Math.max(0,Math.min(1,Double.isFinite(conf)?conf:.8)));if(mq.optBoolean("material_doubt",false))doubt=true;
            if(fq.isNull("max_points")){nullMax=true;out.put("max_points",JSONObject.NULL).put("awarded_points",JSONObject.NULL).put("evidence_vector",mq.optJSONArray("evidence_vector")==null?new JSONArray():mq.optJSONArray("evidence_vector"));outQs.put(out);continue;}
            double max=fq.optDouble("max_points",0);JSONArray matrix=fq.getJSONArray("evidence_matrix"),vector=mq.optJSONArray("evidence_vector");if(vector==null)vector=new JSONArray();Map<String,JSONObject> mev=new HashMap<>();
            for(int j=0;j<vector.length();j++){JSONObject e=vector.optJSONObject(j);if(e!=null)mev.put(e.optString("code",""),e);}JSONArray norm=new JSONArray();double pts=0;Double cap=null;
            for(int j=0;j<matrix.length();j++){
                JSONObject r=matrix.getJSONObject(j);String code=r.optString("code","E"+(j+1));JSONObject e=mev.get(code);String state=e==null?"NO_ACREDITADA":e.optString("state","NO_ACREDITADA");String loc=e==null?"[OMITIDA POR CHATGPT]":e.optString("evidence_location","");if(e==null)blocks.put(id+": falta evidencia "+code);
                double a=0;if("ACREDITADA".equals(state))a=r.optDouble("points",0);else if("PARCIALMENTE_ACREDITADA".equals(state)){if(r.optBoolean("partial_allowed",false))a=r.optDouble("partial_points",0);else{blocks.put(id+"/"+code+": parcial no autorizado");state="NO_ACREDITADA";}}else if(!"NO_ACREDITADA".equals(state)&&!"NO_EVALUABLE_ILEGIBILIDAD".equals(state)){blocks.put(id+"/"+code+": estado inválido");state="NO_ACREDITADA";}
                pts+=a;if(r.optBoolean("essential",false)&&!"ACREDITADA".equals(state)&&!r.isNull("cap_if_missing")){double c=r.optDouble("cap_if_missing",Double.NaN);if(Double.isFinite(c))cap=cap==null?c:Math.min(cap,c);}
                norm.put(new JSONObject().put("code",code).put("description",r.optString("description",code)).put("state",state).put("awarded_points",round(a)).put("evidence_location",loc));
            }
            pts=Math.max(0,Math.min(max,pts));if(cap!=null)pts=Math.min(pts,cap);out.put("max_points",round(max)).put("awarded_points",round(pts)).put("evidence_vector",norm);totalMax+=max;totalAward+=pts;outQs.put(out);
        }
        double pen=Math.max(0,model.optDouble("presentation_penalty",0));if("No evaluar".equals(presentationMode))pen=0;else if("Hasta 0,5 puntos".equals(presentationMode))pen=Math.min(.5,pen);else if("Hasta 1,0 punto".equals(presentationMode))pen=Math.min(1,pen);else pen=0;
        String status=(doubt||blocks.length()>0)?"blocked":((nullMax||totalMax<=0)?"provisional":"definitive");JSONObject out=new JSONObject();out.put("student_code",studentCode).put("fmc_version",fmc.optString("fmc_version","1")).put("status",status).put("question_results",outQs).put("presentation_penalty",round(pen)).put("presentation_penalty_reason",model.optString("presentation_penalty_reason","")).put("technical_report",model.optString("technical_report","")).put("feedback",model.optJSONArray("feedback")==null?new JSONArray():model.optJSONArray("feedback")).put("consistency_notes",model.optJSONArray("consistency_notes")==null?new JSONArray():model.optJSONArray("consistency_notes")).put("blocking_issues",blocks);
        JSONObject t=new JSONObject();if(nullMax||totalMax<=0){t.put("max_points",JSONObject.NULL).put("awarded_points",JSONObject.NULL).put("raw_grade_0_10",JSONObject.NULL).put("presentation_penalty",round(pen)).put("final_grade_0_10",JSONObject.NULL);}else{double raw=totalAward/totalMax*10,fin=Math.max(0,raw-pen);t.put("max_points",round(totalMax)).put("awarded_points",round(totalAward)).put("raw_grade_0_10",round(raw)).put("presentation_penalty",round(pen)).put("final_grade_0_10",round(fin));}out.put("totals",t);return out;
    }
    private static void copy(JSONArray a,JSONArray b){if(a!=null)for(int i=0;i<a.length();i++){String s=a.optString(i,"");if(!s.isEmpty())b.put(s);}}
    private static double round(double v){return Math.round(v*10000d)/10000d;}
}
