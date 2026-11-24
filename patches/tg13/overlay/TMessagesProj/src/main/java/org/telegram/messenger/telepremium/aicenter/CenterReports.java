package org.telegram.messenger.telepremium.aicenter;

import java.util.*;
import org.json.*;
import java.util.regex.Pattern;

/** Structured claims and validated evidence. Provider prose never becomes a navigation target. */
public final class CenterReports {
    private CenterReports(){}
    public static boolean personal(String s){String t=s.toLowerCase(Locale.ROOT);return t.matches("(?s).*(соболезну|сочувствую|умерла? |похорон|condolence|rest in peace|my condolences).*");}
    public static String clean(String s){return s==null?"":s.replaceAll("\\[(?:msg|message|source):[^\\]]*\\]","").trim();}
    public static final class Evidence {
        public final CenterData.Message message;public final String quote;public final long timeMs;public final int page;
        Evidence(CenterData.Message m,String q,long ms,int p){message=m;quote=q;timeMs=ms;page=p;}
        public JSONObject json()throws JSONException{return new JSONObject().put("account",message.account).put("chat",message.dialog).put("message",message.id).put("quote",quote).put("timeMs",timeMs).put("page",page);}
    }
    public static final class Claim {
        public String title="",text="",topic="",certainty="single";public final List<Evidence> evidence=new ArrayList<>();
        public boolean verified(){return !evidence.isEmpty();}
        public JSONObject json()throws JSONException{JSONArray a=new JSONArray();for(Evidence e:evidence)a.put(e.json());return new JSONObject().put("title",title).put("text",text).put("topic",topic).put("certainty",certainty).put("evidence",a);}
    }
    public static final class Report {
        public String id=UUID.randomUUID().toString(),question="";public long created=System.currentTimeMillis(),from,to;
        public int indexed,analyzed;public boolean complete;public final List<Claim> claims=new ArrayList<>();public final List<String> unclear=new ArrayList<>();
        public JSONObject json()throws JSONException{JSONArray a=new JSONArray();for(Claim c:claims)a.put(c.json());return new JSONObject().put("id",id).put("question",question).put("created",created).put("from",from).put("to",to).put("indexed",indexed).put("analyzed",analyzed).put("complete",complete).put("claims",a).put("unclear",new JSONArray(unclear));}
    }
    public static Report validate(JSONObject response,List<CenterData.Message> index){
        Map<String,CenterData.Message> lookup=new HashMap<>();for(CenterData.Message m:index)lookup.put(m.qualified(),m);
        Report r=new Report();r.id=response.optString("id",r.id);r.question=clean(response.optString("question"));r.created=response.optLong("created",r.created);r.from=response.optLong("from");r.to=response.optLong("to");r.indexed=response.optInt("indexed");r.analyzed=response.optInt("analyzed");r.complete=response.optBoolean("complete");
        JSONArray claims=response.optJSONArray("claims");for(int i=0;claims!=null&&i<Math.min(500,claims.length());i++){
            JSONObject c=claims.optJSONObject(i);if(c==null)continue;Claim claim=new Claim();claim.title=clean(c.optString("title"));claim.text=clean(c.optString("text"));claim.topic=clean(c.optString("topic"));String certainty=c.optString("certainty","single");claim.certainty="rumor".equals(certainty)?"rumor":"single";
            JSONArray evidence=c.optJSONArray("evidence");Set<String> seen=new HashSet<>();for(int j=0;evidence!=null&&j<Math.min(100,evidence.length());j++){
                JSONObject e=evidence.optJSONObject(j);if(e==null)continue;CenterData.Message m=lookup.get(e.optInt("account")+":"+e.optLong("chat")+":"+e.optInt("message"));
                if(m==null||m.protectedMedia||!seen.add(m.qualified()))continue;
                String quote=e.optString("quote").trim();String actual=m.text+"\n"+m.ocr+"\n"+m.transcript;
                if(quote.isEmpty()||!actual.contains(quote))continue;
                // Display a slice of the original, never the provider's fabricated formatting.
                int start=actual.indexOf(quote);String excerpt=actual.substring(start,Math.min(actual.length(),start+Math.min(160,quote.length())));
                long time=Math.max(0,e.optLong("timeMs"));int page=Math.max(0,e.optInt("page"));if(time>0&&(m.duration<=0||time>=m.duration)||page>0&&(m.pages<=0||page>m.pages))continue;
                claim.evidence.add(new Evidence(m,excerpt,time,page));
            }
            if(claim.evidence.size()>1&&!"rumor".equals(claim.certainty))claim.certainty="multiple";
            if(!claim.title.isEmpty()||!claim.text.isEmpty())r.claims.add(claim);
        }
        JSONArray unclear=response.optJSONArray("unclear");for(int i=0;unclear!=null&&i<unclear.length();i++)r.unclear.add(clean(unclear.optString(i)));return r;
    }
    public static JSONObject parse(String raw)throws JSONException{
        String s=raw.trim();if(s.startsWith("```")){int nl=s.indexOf('\n'),end=s.lastIndexOf("```");if(nl<0||end<=nl)throw new JSONException("Invalid structured response");s=s.substring(nl+1,end).trim();}
        JSONObject o=new JSONObject(s);if(o.optJSONArray("claims")==null)throw new JSONException("Missing report claims");return o;
    }
    /** Every character participates, including messages longer than one model chunk. */
    public static List<JSONArray> chunks(List<CenterData.Message> messages,boolean personal,int maxChars)throws JSONException{
        ArrayList<JSONArray> batches=new ArrayList<>();JSONArray batch=new JSONArray();int length=0;
        for(CenterData.Message m:messages){if(m.deleted||m.protectedMedia||(!personal&&m.personal))continue;
            String text=m.searchable();for(int off=0;off<Math.max(1,text.length());){int end=Math.min(text.length(),off+Math.max(512,maxChars/2));String part=text.substring(off,end);
                JSONObject o=new JSONObject().put("account",m.account).put("chat",m.dialog).put("message",m.id).put("source",m.sourceTitle).put("date",m.date).put("author",m.authorName).put("kind",m.kind).put("partStart",off).put("text",part);
                int size=o.toString().length();if(length+size>maxChars&&batch.length()>0){batches.add(batch);batch=new JSONArray();length=0;}batch.put(o);length+=size;if(end==text.length())break;off=end;}
        }if(batch.length()>0)batches.add(batch);return batches;
    }
    public static List<CenterData.Message> context(List<CenterData.Message> index,CenterData.Message anchor){
        ArrayList<CenterData.Message> same=new ArrayList<>();for(CenterData.Message m:index)if(m.account==anchor.account&&m.dialog==anchor.dialog)same.add(m);same.sort(Comparator.comparingInt(m->m.id));
        int pos=0;for(int i=0;i<same.size();i++)if(same.get(i).id==anchor.id){pos=i;break;}return new ArrayList<>(same.subList(Math.max(0,pos-5),Math.min(same.size(),pos+6)));
    }
}
