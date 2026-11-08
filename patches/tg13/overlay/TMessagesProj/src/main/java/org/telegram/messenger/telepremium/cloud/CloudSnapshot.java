package org.telegram.messenger.telepremium.cloud;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class CloudSnapshot {
    public static final int SCHEMA_VERSION = 1;
    public String cloudId = UUID.randomUUID().toString();
    public long ownerUserId;
    public long generation;
    public long updatedAt;
    public String clientId;
    public long journalGeneration;
    public JSONArray mediaLibrary = new JSONArray();
    public final List<CloudNode> nodes = new ArrayList<>();

    public String encode() throws Exception {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("cloudId", cloudId);
        root.put("ownerUserId", ownerUserId);
        root.put("generation", generation);
        root.put("updatedAt", updatedAt);
        root.put("clientId", clientId);
        root.put("journalGeneration", journalGeneration);
        JSONArray a = new JSONArray();
        for (CloudNode n : nodes) a.put(n.toJson());
        root.put("nodes", a);
        root.put("mediaLibrary", mediaLibrary);
        return root.toString();
    }

    public static CloudSnapshot decode(String raw) throws Exception {
        JSONObject root = new JSONObject(raw);
        if (root.optInt("schemaVersion") != SCHEMA_VERSION) throw new IllegalStateException("Unsupported Cloud FS schema");
        CloudSnapshot s = new CloudSnapshot();
        s.cloudId=root.getString("cloudId"); s.ownerUserId=root.optLong("ownerUserId");
        s.generation=root.optLong("generation"); s.updatedAt=root.optLong("updatedAt");
        s.clientId=root.optString("clientId", null); s.journalGeneration=root.optLong("journalGeneration");
        s.mediaLibrary=mergeMediaLibrary(new JSONArray(), root.optJSONArray("mediaLibrary"));
        JSONArray a=root.optJSONArray("nodes");
        if (a != null) for(int i=0;i<a.length();i++) s.nodes.add(CloudNode.fromJson(a.getJSONObject(i)));
        return s;
    }
    /** Observed-remove records. A deletion permanently wins over edits of that incarnation. */
    public static JSONArray mergeMediaLibrary(JSONArray first, JSONArray second) {
        java.util.TreeMap<String, JSONObject> records = new java.util.TreeMap<>();
        try {
            for (JSONArray source : new JSONArray[]{first, second}) if (source != null) {
                if (source.length() > 20000) throw new IllegalArgumentException("Media library is too large");
                for (int i=0;i<source.length();i++) {
                    JSONObject row=source.getJSONObject(i);
                    String id=row.getString("id"), birth=row.getString("birth");
                    if(id.length()>256 || birth.length()>80 || id.isEmpty() || birth.isEmpty() || row.optLong("clock")<0 || row.optLong("clock")>Long.MAX_VALUE-1000)
                        throw new IllegalArgumentException("Invalid media record");
                    String key=id+"@"+birth;JSONObject prior=records.get(key);
                    boolean removed=row.optBoolean("deleted") || (prior!=null && prior.optBoolean("deleted"));
                    boolean legacy=row.optBoolean("legacy") || (prior!=null && prior.optBoolean("legacy"));
                    JSONObject winner=row;
                    if(prior!=null) {
                        int order=Long.compare(row.optLong("clock"),prior.optLong("clock"));
                        if(order==0)order=row.optString("actor").compareTo(prior.optString("actor"));
                        if(order==0)order=row.toString().compareTo(prior.toString());
                        if(order<0)winner=prior;
                    }
                    JSONObject merged=new JSONObject(winner.toString());
                    merged.put("deleted",removed);
                    if(legacy)merged.put("legacy",true);
                    if(removed)merged.put("value",new JSONObject());
                    records.put(key,merged);
                }
            }
            if(records.size()>20000)throw new IllegalArgumentException("Media library is too large");
            java.util.HashSet<String> removedIds=new java.util.HashSet<>();
            for(JSONObject row:records.values())if(row.optBoolean("deleted"))removedIds.add(row.getString("id"));
            JSONArray out=new JSONArray();
            for(JSONObject row:records.values()){
                if(row.optBoolean("legacy") && removedIds.contains(row.getString("id")))row.put("deleted",true).put("value",new JSONObject());
                out.put(row);
            }
            return out;
        } catch(Exception e){throw new IllegalArgumentException("Invalid media library state",e);}
    }

}
