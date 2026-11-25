import java.util.*;
import org.json.*;
import org.telegram.messenger.telepremium.aicenter.*;
public final class CenterTranslationTest {
 static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception{
  String source="[00:00:01.250 --> 00:00:04.125] [speaker:0/1] First words\n[00:00:05.000 --> 00:00:09.000] [speaker:0/2] Other words\n";
  List<JSONArray> batches=CenterTranslation.batches(source,12000);JSONObject response=CenterTranslation.parse("```json\n{\"parts\":[{\"id\":\"1:0\",\"text\":\"Другие слова\"},{\"id\":\"0:0\",\"text\":\"Первые слова\"}]}\n```");JSONObject translations=CenterTranslation.validate(batches.get(0),response);String result=CenterTranslation.apply(source,translations,10000);List<CenterTranscript.Segment> segments=CenterTranscript.parse(result);check(segments.get(0).start==1250&&segments.get(0).end==4125&&segments.get(0).speaker.equals("0/1"),"translation cannot alter source timings or speakers");check(segments.get(0).text.equals("Первые слова")&&segments.get(1).text.equals("Другие слова"),"provider result order cannot reorder dialogue");
  try{CenterTranslation.validate(batches.get(0),new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("id","0:0").put("text","one")).put(new JSONObject().put("id","0:0").put("text","duplicate"))));throw new AssertionError("duplicate id accepted");}catch(JSONException expected){}
  JSONObject partial=new JSONObject().put("0:0","Только первый");check(CenterTranscript.parse(CenterTranslation.apply(source,partial,10000)).size()==1,"partial output contains completed segments only");
  String longText=String.join("",Collections.nCopies(15000,"а🚀"));List<JSONArray> longParts=CenterTranslation.batches("[00:00:00] "+longText,12000);StringBuilder reconstructed=new StringBuilder();for(JSONArray batch:longParts)for(int i=0;i<batch.length();i++)reconstructed.append(batch.optJSONObject(i).optString("text"));check(reconstructed.toString().equals(longText),"large translated inputs are never truncated and Unicode survives boundaries");
  System.out.println("CenterTranslationTest OK: exact timestamps/speakers, unordered provider output, duplicate rejection, partial recovery and complete Unicode input");
 }
}
