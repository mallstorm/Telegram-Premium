package org.telegram.messenger.telepremium.cloud;

import java.util.*;
import java.util.regex.*;

/** Short display names live inside the encrypted index; source/device filenames are untouched. */
public final class CloudImageNames {
    private static final Pattern SHORT=Pattern.compile("^Image_([0-9]{1,18})[.][0-9a-f]{8}[.][a-z0-9]+$",Pattern.CASE_INSENSITIVE);
    private static final Pattern GENERIC=Pattern.compile("^(?:file|image|img|photo)_[0-9a-f]{16,64}[.](?:png|jpe?g|webp|gif|heic|heif|avif|bmp|tiff?)$",Pattern.CASE_INSENSITIVE);
    private static final Set<String> EXT=new HashSet<>(Arrays.asList("png","jpg","jpeg","webp","gif","heic","heif","avif","bmp","tif","tiff","svg"));
    public static boolean isImage(CloudNode node){return node!=null&&node.type==CloudNode.TYPE_FILE&&((node.mimeType!=null&&node.mimeType.toLowerCase(Locale.ROOT).startsWith("image/"))||EXT.contains(extension(node.name)));}
    private static String extension(String name){if(name==null)return "";int dot=name.lastIndexOf('.');return dot<0?"":name.substring(dot+1).toLowerCase(Locale.ROOT);}
    public static long nextOrdinal(List<CloudNode> nodes){long max=0;for(CloudNode node:nodes){Matcher m=SHORT.matcher(node.name==null?"":node.name);if(m.matches())max=Math.max(max,Long.parseLong(m.group(1)));}return max+1;}
    public static String name(CloudNode node,long ordinal){
        String ext=extension(node.name);
        if(!EXT.contains(ext)){
            String mime=node.mimeType==null?"":node.mimeType.toLowerCase(Locale.ROOT);
            if(mime.equals("image/jpeg"))ext="jpg";else if(mime.equals("image/png"))ext="png";else if(mime.equals("image/webp"))ext="webp";else if(mime.equals("image/gif"))ext="gif";else if(mime.equals("image/avif"))ext="avif";else if(mime.equals("image/heic"))ext="heic";else ext="img";
        }
        try{
            byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest((node.id==null?"":node.id).getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder suffix=new StringBuilder();for(int i=0;i<4;i++)suffix.append(String.format(Locale.ROOT,"%02x",digest[i]&255));
            return "Image_"+ordinal+"."+suffix+"."+ext;
        }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public static void assignNew(CloudNode node,List<CloudNode> existing){
        if(!isImage(node))return;if(node.originalName==null||node.originalName.isEmpty())node.originalName=node.name;node.name=name(node,nextOrdinal(existing));
    }
    /** Only old generated hash names are shortened; existing user-chosen names remain intact. */
    public static int compactGeneric(List<CloudNode> nodes,long now){
        long ordinal=nextOrdinal(nodes);int changed=0;ArrayList<CloudNode> ordered=new ArrayList<>(nodes);
        ordered.sort(Comparator.comparingLong((CloudNode node)->node.createdAt).thenComparing(node->node.id==null?"":node.id));
        for(CloudNode node:ordered)if(node.state==CloudNode.STATE_ACTIVE&&isImage(node)&&GENERIC.matcher(node.name==null?"":node.name).matches()){
            if(node.originalName==null||node.originalName.isEmpty())node.originalName=node.name;node.name=name(node,ordinal++);node.modifiedAt=Math.max(now,node.modifiedAt+1);changed++;
        }
        return changed;
    }
    private CloudImageNames(){}
}
