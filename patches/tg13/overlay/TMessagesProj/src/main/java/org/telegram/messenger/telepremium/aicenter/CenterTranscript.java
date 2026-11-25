package org.telegram.messenger.telepremium.aicenter;

import java.util.*;
import java.util.regex.*;
import org.json.*;

/** Timestamped speech with optional anonymous, fragment-scoped speaker labels. */
public final class CenterTranscript {
  public static final class Segment {
    public long start,end;
    public String text,speaker="";
    private boolean explicitEnd;
    public Segment(long start,long end,String text){this.start=start;this.end=end;this.text=text;}
  }
  private static final String TIME="\\d+:\\d+(?::\\d+)?(?:[.,]\\d{1,3})?";
  private static final Pattern LINE=Pattern.compile("(?ms)^\\[("+TIME+")(?:[ \\t]*-->[ \\t]*("+TIME+"))?\\][ \\t]*(?:\\[speaker:(\\d+/\\d+)\\][ \\t]*)?(.*?)(?=^\\[\\d+:\\d+|\\z)");
  public static String stamp(long ms){return String.format(Locale.ROOT,"%02d:%02d:%02d",ms/3600000,(ms/60000)%60,(ms/1000)%60);}
  public static String precise(long ms){return stamp(ms)+"."+String.format(Locale.ROOT,"%03d",ms%1000);}
  private static long readTime(String value){String[] decimal=value.replace(',','.').split("\\.",2),parts=decimal[0].split(":");long first=Long.parseLong(parts[0]),second=Long.parseLong(parts[1]),third=parts.length==3?Long.parseLong(parts[2]):-1;if(first>1000000||second>=60||third>=60)throw new NumberFormatException();long result=(third<0?first*60+second:first*3600+second*60+third)*1000;if(decimal.length>1)result+=Integer.parseInt((decimal[1]+"000").substring(0,3));return result;}
  public static List<Segment> parse(String text){List<Segment> result=new ArrayList<>();Matcher matcher=LINE.matcher(text);while(matcher.find())try{long start=readTime(matcher.group(1));Segment segment=new Segment(start,matcher.group(2)==null?start+35000:readTime(matcher.group(2)),matcher.group(4).trim());segment.explicitEnd=matcher.group(2)!=null;segment.speaker=matcher.group(3)==null?"":matcher.group(3);if(segment.end>segment.start)result.add(segment);}catch(NumberFormatException invalid){}
    result.sort(Comparator.comparingLong(segment->segment.start));for(int i=0;i+1<result.size();i++)if(!result.get(i).explicitEnd)result.get(i).end=result.get(i+1).start;return result;}
  public static List<Segment> parse(String text,long duration){List<Segment> result=parse(text);if(duration>0){result.removeIf(segment->segment.start>=duration);for(Segment segment:result)segment.end=Math.min(segment.end,duration);}return result;}
  public static String encode(List<Segment> segments){StringBuilder result=new StringBuilder();for(Segment segment:segments)if(segment.start>=0&&segment.end>segment.start){result.append('[').append(precise(segment.start)).append(" --> ").append(precise(segment.end)).append("] ");if(segment.speaker.matches("\\d+/\\d+"))result.append("[speaker:").append(segment.speaker).append("] ");result.append(segment.text.trim()).append('\n');}return result.toString();}
  public static String speakerLabel(String speaker){if(!speaker.matches("\\d+/\\d+"))return "";String[] parts=speaker.split("/");try{return "S"+parts[1]+"@"+stamp(Long.parseLong(parts[0]));}catch(NumberFormatException invalid){return "";}}
  /** Provider times are relative to this upload fragment. Labels never identify a person or merge voices across fragments. */
  public static List<Segment> fromProvider(JSONObject response,long base,long until,boolean speakers){List<Segment> result=new ArrayList<>();Map<String,Integer> ids=new LinkedHashMap<>();JSONArray rows=response.optJSONArray("segments");if(rows==null)rows=response.optJSONArray("utterances");for(int i=0;rows!=null&&i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row==null)continue;try{double start=Double.parseDouble(row.optString("start","NaN")),end=Double.parseDouble(row.optString("end","NaN"));if(Double.isNaN(start)||Double.isInfinite(start)||Double.isNaN(end)||Double.isInfinite(end)||start<0||end<=start||start*1000>=until-base)continue;String text=row.optString("text").trim();if(text.isEmpty())continue;Segment segment=new Segment(base+Math.round(start*1000),Math.min(until,base+Math.round(end*1000)),text);segment.explicitEnd=true;String key=row.optString("speaker").trim();if(speakers&&!key.isEmpty()){if(!ids.containsKey(key))ids.put(key,ids.size()+1);segment.speaker=base+"/"+ids.get(key);}if(segment.end>segment.start)result.add(segment);}catch(NumberFormatException invalid){}}
    if(result.isEmpty()&&until>base&&!response.optString("text").trim().isEmpty()){Segment fallback=new Segment(base,until,response.optString("text").trim());fallback.explicitEnd=true;result.add(fallback);}result.sort(Comparator.comparingLong(segment->segment.start));return result;}
  public static String subtitles(List<Segment> segments,boolean vtt){StringBuilder result=new StringBuilder(vtt?"WEBVTT\n\n":"");int index=0;for(Segment segment:segments){if(segment.start<0||segment.end<=segment.start)continue;if(!vtt)result.append(++index).append('\n');result.append(vtt?precise(segment.start):precise(segment.start).replace('.',',')).append(" --> ").append(vtt?precise(segment.end):precise(segment.end).replace('.',',')).append('\n');String speaker=speakerLabel(segment.speaker),text=segment.text.replace("-->","→");if(vtt){text=text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");if(!speaker.isEmpty())result.append("<v ").append(speaker).append('>');result.append(text);if(!speaker.isEmpty())result.append("</v>");}else{if(!speaker.isEmpty())result.append('[').append(speaker).append("] ");result.append(text);}result.append("\n\n");}return result.toString();}
}
