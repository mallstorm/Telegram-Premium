package org.telegram.messenger.telepremium.xray;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;
import java.util.Map;
import java.util.HashSet;

/** One core, two loopback listeners per profile, explicit inbound -> outbound routing. */
public final class XrayConfig {
    public static final int MAX_PROFILES = 10;
    private XrayConfig() {}
    public static void validateOutbound(JSONObject node) throws Exception {
        String protocol = node.optString("protocol");
        if (!java.util.Arrays.asList("vless", "vmess", "trojan", "shadowsocks", "socks", "http", "hysteria2").contains(protocol))
            throw new IllegalArgumentException("Unsupported outbound protocol: " + protocol);
        if (node.has("proxySettings") || node.has("sendThrough")) throw new IllegalArgumentException("Use independent outbound profiles");
        JSONObject stream = node.optJSONObject("streamSettings");
        JSONObject sock = stream == null ? null : stream.optJSONObject("sockopt");
        if (sock != null && sock.has("dialerProxy")) throw new IllegalArgumentException("A profile cannot reference a different outbound");
        if (node.toString().length() > 262144) throw new IllegalArgumentException("Profile is too large");
    }
    /** Accept a single outbound, an array, or outbounds from a full root configuration. */
    public static JSONArray importJson(String text) throws Exception {
        String source=text.trim();JSONArray nodes;
        if(source.startsWith("["))nodes=new JSONArray(source);
        else {JSONObject root=new JSONObject(source);nodes=root.optJSONArray("outbounds");if(nodes==null)nodes=new JSONArray().put(root);}
        JSONArray accepted=new JSONArray();
        for(int i=0;i<nodes.length();i++){
            JSONObject node=nodes.getJSONObject(i);String protocol=node.optString("protocol");
            if(protocol.equals("freedom")||protocol.equals("blackhole")||protocol.equals("dns"))continue;
            validateOutbound(node);accepted.put(new JSONObject(node.toString()));
        }
        if(accepted.length()==0)throw new IllegalArgumentException("Нет поддерживаемых профилей Xray");
        if(accepted.length()>MAX_PROFILES)throw new IllegalArgumentException("Можно сохранить до 10 профилей");
        return accepted;
    }
    public static JSONObject build(List<XrayProfile> profiles, Map<String,int[]> ports) throws Exception {
        if (profiles.size() > MAX_PROFILES) throw new IllegalArgumentException("Maximum 10 profiles");
        JSONArray inbounds = new JSONArray(), outbounds = new JSONArray(), rules = new JSONArray();
        outbounds.put(new JSONObject().put("tag", "block").put("protocol", "blackhole"));
        outbounds.put(new JSONObject().put("tag", "direct").put("protocol", "freedom"));
        HashSet<String> ids = new HashSet<>(); HashSet<Integer> usedPorts = new HashSet<>();
        for (XrayProfile p : profiles) {
            if (!ids.add(p.id)) throw new IllegalArgumentException("Duplicate profile");
            validateOutbound(p.outbound); int[] pair = ports.get(p.id);
            if (pair == null || pair.length != 2) throw new IllegalArgumentException("Missing listener ports");
            JSONArray tags = new JSONArray();
            for (int i = 0; i < 2; i++) {
                if (pair[i] < 1024 || pair[i] > 65535 || !usedPorts.add(pair[i])) throw new IllegalArgumentException("Invalid or duplicate port");
                String tag = p.id + (i == 0 ? "-socks" : "-http"); tags.put(tag);
                inbounds.put(new JSONObject().put("tag",tag).put("listen","127.0.0.1").put("port",pair[i])
                        .put("protocol",i == 0 ? "socks" : "http").put("settings",new JSONObject().put("auth","noauth").put("udp",false)));
            }
            JSONObject outbound = new JSONObject(p.outbound.toString());outbound.put("tag",p.id);outbounds.put(outbound);
            if (p.bypassLan) rules.put(new JSONObject().put("type","field").put("inboundTag",tags).put("ip",new JSONArray()
                    .put("127.0.0.0/8").put("10.0.0.0/8").put("172.16.0.0/12").put("192.168.0.0/16")
                    .put("169.254.0.0/16").put("::1/128").put("fc00::/7").put("fe80::/10")).put("outboundTag","direct"));
            rules.put(new JSONObject().put("type","field").put("inboundTag",tags).put("outboundTag",p.id));
        }
        return new JSONObject().put("log",new JSONObject().put("loglevel","none"))
                .put("inbounds",inbounds).put("outbounds",outbounds)
                .put("routing",new JSONObject().put("domainStrategy","AsIs").put("rules",rules));
    }
}
