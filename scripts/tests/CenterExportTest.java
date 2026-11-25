import org.telegram.messenger.telepremium.aicenter.*;
import org.json.*;
import java.util.*;
import java.io.*;
public class CenterExportTest {
 static void check(boolean v,String why){if(!v)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception{
  CenterData.Message m=new CenterData.Message();m.account=0;m.owner=3;m.dialog=-100;m.source="c:100";m.id=42;m.author=7;m.authorName="Private Name";m.accountName="Private Account";m.sourceTitle="<script>alert(1)</script>";m.text="first quote and second quote";
  JSONObject e=new JSONObject().put("account",0).put("chat",-100).put("message",42).put("quote","first quote");JSONObject claim=new JSONObject().put("title","<img src=x onerror=alert(1)>").put("text","[msg:42] safe Private Name Private Account").put("evidence",new JSONArray().put(e));
  CenterReports.Report r=CenterReports.validate(new JSONObject().put("claims",new JSONArray().put(claim).put(new JSONObject(claim.toString()).put("evidence",new JSONArray().put(new JSONObject(e.toString()).put("quote","second quote"))))),Arrays.asList(m));r.question="Report";r.from=1;r.to=100;r.indexed=r.analyzed=1;
  CenterExport.Options o=new CenterExport.Options();o.anonymize=true;o.images=false;
  StringWriter html=new StringWriter();CenterExport.html(r,o,k->k,null,null,html);String h=html.toString();check(!h.contains("<script>"),"HTML scripts escaped");check(!h.contains("<img src=x"),"HTML attributes escaped");check(!h.contains("Private Name")&&!h.contains("Private Account"),"author/account anonymization");check(h.contains("https://t.me/c/100/42"),"only original message links");check(!h.contains("[msg:"),"no raw tokens");
  StringWriter md=new StringWriter();CenterExport.md(r,o,k->k,null,null,md);check(md.toString().contains("first quote"),"Markdown evidence");check(!md.toString().contains("Private Name"),"Markdown privacy");
  check(CenterExport.csvRow("=HYPERLINK(\"x\")").startsWith("\"'="),"CSV formula");check(CenterExport.csvRow("  =HYPERLINK(\"x\")").startsWith("\"'  ="),"CSV leading spaces");check(CenterExport.csvRow(-3).startsWith("\"-3"),"CSV numeric values");
  CenterReports.Report restored=CenterReports.restore(CenterReports.archive(r),Collections.emptyList());check(restored.claims.get(0).verified()&&restored.claims.get(1).verified(),"archival evidence survives index pruning, including multiple excerpts");
  m.transcript="[00:00:00] Full transcript first segment\n[00:00:35] Content outside the cited quotation";r.dataset.add(m);
  CenterReports.Report durable=CenterReports.restore(CenterReports.archive(r),Collections.emptyList());check(CenterReports.transcripts(durable).get(0).transcript.contains("outside the cited quotation"),"full transcripts survive index pruning");
  StringWriter full=new StringWriter();CenterExport.html(durable,o,k->k,null,null,full);check(full.toString().contains("outside the cited quotation"),"HTML exports full archived transcript");
  CenterData.Message other=new CenterData.Message();other.account=0;other.dialog=-100;other.id=100;check(CenterReports.context(Arrays.asList(other),m).stream().anyMatch(item->item.id==42),"pruned context includes the actual anchor");
  check(CenterExport.Options.parse(o.json()).blocks.equals(o.blocks),"builder block order persistence");
  System.out.println("CenterExportTest OK: HTML escaping, source links, author/account privacy, CSV injection, Markdown, archival quotations and builder order");
 }
}