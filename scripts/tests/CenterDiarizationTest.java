import java.util.*;
import org.json.*;
import org.telegram.messenger.telepremium.aicenter.*;
public final class CenterDiarizationTest {
 static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception{
  JSONObject response=new JSONObject().put("text","Hello world").put("segments",new JSONArray().put(new JSONObject().put("start",1.25).put("end",3.5).put("text","Hello").put("speaker","Alice")).put(new JSONObject().put("start",4).put("end",7.125).put("text","world").put("speaker","Bob")));
  List<CenterTranscript.Segment> first=CenterTranscript.fromProvider(response,35000,45000,true);check(first.size()==2&&first.get(0).start==36250&&first.get(1).end==42125,"provider timestamps shifted to original media exactly");check(!first.get(0).speaker.equals(first.get(1).speaker),"different anonymous speaker labels");String encoded=CenterTranscript.encode(first);check(!encoded.contains("Alice")&&!encoded.contains("Bob"),"speaker labels do not infer identities");List<CenterTranscript.Segment> decoded=CenterTranscript.parse(encoded);check(decoded.get(0).end==38500&&decoded.get(1).speaker.equals(first.get(1).speaker),"speaker and explicit end survive edits/restarts");
  List<CenterTranscript.Segment> second=CenterTranscript.fromProvider(response,45000,55000,true);check(!first.get(0).speaker.equals(second.get(0).speaker),"no false voice identity across upload fragments");check(CenterTranscript.subtitles(decoded,false).contains("00:00:36,250 --> 00:00:38,500"),"SRT precision");check(CenterTranscript.subtitles(decoded,true).contains("<v S1@00:00:35>"),"WebVTT speaker cues");
  check(CenterTranscript.fromProvider(response,0,10000,false).get(0).speaker.isEmpty(),"unsupported engine never invents speaker labels");check(CenterTranscript.fromProvider(new JSONObject().put("text","fallback"),0,10000,true).get(0).end==10000,"plain providers retain fragment time bounds");
  check(CenterTranscript.parse("[00:00:01.250 --> 00:00:01.000] invalid").isEmpty(),"invalid explicit interval rejected");
  System.out.println("CenterDiarizationTest OK: anonymous scoped speakers, precise intervals, persistence, SRT/VTT, provider fallback and malformed time rejection");
 }
}
