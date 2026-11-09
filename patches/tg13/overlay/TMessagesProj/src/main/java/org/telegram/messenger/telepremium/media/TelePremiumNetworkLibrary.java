package org.telegram.messenger.telepremium.media;

import android.text.TextUtils;
import org.json.JSONObject;
import java.util.*;

/** All entries are per-library-owner records in the encrypted V3 settings catalog. */
public final class TelePremiumNetworkLibrary {
    public static final class IptvPlaylist {
        public final String name,url,artworkUri;
        IptvPlaylist(String name,String url,String artworkUri){this.name=name;this.url=url;this.artworkUri=artworkUri;}
    }
    private TelePremiumNetworkLibrary(){}
    private static List<IptvPlaylist> list(String kind){ArrayList<IptvPlaylist> out=new ArrayList<>();for(JSONObject row:TelePremiumMediaLibraryStore.values(kind))out.add(new IptvPlaylist(row.optString("name"),row.optString("url"),row.optString("artworkUri",null)));return out;}
    public static List<IptvPlaylist> getIptvPlaylists(){return list("iptv");}
    public static List<IptvPlaylist> getYoutubeChannels(){return list("youtube");}
    private static void add(String kind,String name,String url){try{TelePremiumMediaLibraryStore.readd(kind,url,new JSONObject().put("name",TextUtils.isEmpty(name)?url:name).put("url",url));}catch(Exception e){TelePremiumMediaLibrarySync.localError("Не удалось сохранить источник");}}
    public static void addYoutubeChannel(String name,String url){add("youtube",name,url);}
    public static void removeYoutubeChannel(String url){TelePremiumMediaLibraryStore.remove("youtube",url);}
    public static void addIptvPlaylist(String name,String url){add("iptv",name,url);}
    public static void addIptvFile(String name,String url,String text)throws Exception {
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        try(java.util.zip.GZIPOutputStream gzip=new java.util.zip.GZIPOutputStream(bytes)){gzip.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        TelePremiumMediaLibraryStore.readd("iptv",url,new JSONObject().put("name",name).put("url",url).put("inlineGzip",android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP)));
    }
    public static String inlinePlaylist(String url)throws Exception {
        for(JSONObject row:TelePremiumMediaLibraryStore.values("iptv"))if(url.equals(row.optString("url"))&&row.has("inlineGzip")){
            byte[] compressed=android.util.Base64.decode(row.getString("inlineGzip"),android.util.Base64.NO_WRAP);
            try(java.util.zip.GZIPInputStream input=new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(compressed));java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
                byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){if(out.size()+n>4*1024*1024)throw new java.io.IOException("Плейлист слишком большой");out.write(buffer,0,n);}return new String(out.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }
    public static void removeIptvPlaylist(String url){TelePremiumMediaLibraryStore.remove("iptv",url);}
    public static void updateYoutubeTitle(String url,String name){for(JSONObject row:TelePremiumMediaLibraryStore.values("youtube"))if(url.equals(row.optString("url"))&&!row.optBoolean("customName")){try{row.put("name",name);TelePremiumMediaLibraryStore.put("youtube",url,row,false);}catch(Exception ignored){}return;}}
    public static void renameYoutube(String url,String name){for(JSONObject row:TelePremiumMediaLibraryStore.values("youtube"))if(url.equals(row.optString("url"))){try{row.put("name",name).put("customName",true);TelePremiumMediaLibraryStore.put("youtube",url,row,false);}catch(Exception ignored){}return;}}
    public static void updateYoutubeArtwork(long owner,String url,String artworkUri){
        if(owner<=0||owner!=TelePremiumMediaLibraryStore.owner()||artworkUri==null||!artworkUri.startsWith("https://"))return;
        for(JSONObject row:TelePremiumMediaLibraryStore.values("youtube"))if(url.equals(row.optString("url"))){
            try{row.put("artworkUri",artworkUri);TelePremiumMediaLibraryStore.put("youtube",url,row,false);}catch(Exception ignored){}return;
        }
    }
    public static String youtubeName(String url,String fallback){for(IptvPlaylist p:getYoutubeChannels())if(p.url.equals(url))return p.name;return fallback;}
    public static List<TelePremiumMediaItem> getRadioFavorites(){ArrayList<TelePremiumMediaItem> out=new ArrayList<>();for(JSONObject row:TelePremiumMediaLibraryStore.values("radio"))try{TelePremiumMediaItem item=TelePremiumMediaItem.fromJson(row);item.mediaType=TelePremiumMediaItem.TYPE_STREAM;item.live=true;out.add(item);}catch(Exception ignored){}return out;}
    public static boolean isRadioFavorite(TelePremiumMediaItem item){for(TelePremiumMediaItem f:getRadioFavorites())if(f.uri!=null&&f.uri.equals(item.uri))return true;return false;}
    public static void toggleRadioFavorite(TelePremiumMediaItem item){if(isRadioFavorite(item))TelePremiumMediaLibraryStore.remove("radio",item.uri);else try{TelePremiumMediaLibraryStore.put("radio",item.uri,item.toJson(),true);}catch(Exception ignored){}}
    public static String getRadioCountry(){for(JSONObject row:TelePremiumMediaLibraryStore.values("preferences"))if(row.has("code"))return row.optString("code","RU");String code=Locale.getDefault().getCountry();return TextUtils.isEmpty(code)?"RU":code.toUpperCase(Locale.ROOT);}
    public static String getRadioCountryName(){for(JSONObject row:TelePremiumMediaLibraryStore.values("preferences"))if(row.has("code"))return row.optString("name",null);return null;}
    public static void setRadioCountry(String code,String name){try{TelePremiumMediaLibraryStore.put("preferences","radio",new JSONObject().put("code",code).put("name",name),true);}catch(Exception ignored){}}
}
