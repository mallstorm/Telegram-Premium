package org.telegram.messenger.telepremium.aicenter;

import android.graphics.*;
import android.media.MediaMetadataRetriever;
import android.os.*;
import android.util.Base64;
import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.googlecode.tesseract.android.TessBaseAPI;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

/** Bounded on-device extraction; no automatic external requests. One frame/page in memory at a time. */
public final class CenterMedia {
    private CenterMedia(){}
    public interface Checkpoint {void save(long cursor,int percent)throws Exception;}
    public static void check(CenterData.Message m)throws Exception{
        if(m.protectedMedia||!UserConfig.getInstance(m.account).isClientActivated()||UserConfig.getInstance(m.account).getClientUserId()!=m.owner)throw new SecurityException(CenterText.get("protected"));
        if(m.dialog!=0){TLRPC.Chat chat=MessagesController.getInstance(m.account).getChat(-m.dialog);TLRPC.Message original=CenterSync.original(m);if((m.dialog<0&&(chat==null||chat.noforwards))||original==null||original.noforwards)throw new SecurityException(CenterText.get("protected"));}
        if(!m.cloudNode.isEmpty()){if(!org.telegram.messenger.telepremium.cloud.TelePremiumCloudSecuritySession.isUnlocked(ApplicationLoader.applicationContext))throw new SecurityException(CenterText.get("locked"));org.telegram.messenger.telepremium.cloud.TelePremiumCloudManager manager=org.telegram.messenger.telepremium.cloud.TelePremiumCloudManager.getInstance(m.account);org.telegram.messenger.telepremium.cloud.CloudNode node=manager.findNodeById(m.cloudNode);if(node==null||!manager.isVisible(node)||manager.isProtected(node))throw new SecurityException(CenterText.get("protected"));}
    }
    public static File file(CenterData.Message m,AtomicBoolean cancel)throws Exception{
        check(m);
        if(!m.localUri.isEmpty()){
            File result=new File(ApplicationLoader.applicationContext.getCacheDir(),"aic_input_"+m.key().hashCode());
            android.net.Uri uri=android.net.Uri.parse(m.localUri);try(InputStream in=ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(result)){byte[] buffer=new byte[65536];long size=0;for(int n;in!=null&&(n=in.read(buffer))>=0;){if(cancel.get()||size+n>512L*1024*1024)throw new IOException(CenterText.get("cancel"));out.write(buffer,0,n);size+=n;}}return result;
        }
        if(!m.cloudNode.isEmpty()){
            final java.util.concurrent.CountDownLatch latch=new java.util.concurrent.CountDownLatch(1);final File[] result={null};final String[] error={null};
            AndroidUtilities.runOnUIThread(()->{org.telegram.messenger.telepremium.cloud.TelePremiumCloudManager manager=org.telegram.messenger.telepremium.cloud.TelePremiumCloudManager.getInstance(m.account);org.telegram.messenger.telepremium.cloud.CloudNode node=manager.findNodeById(m.cloudNode);new org.telegram.messenger.telepremium.cloud.CloudDownloadManager(m.account).download(node,(state,file,e)->{if(e!=null){error[0]=e;latch.countDown();}else if(state==org.telegram.messenger.telepremium.cloud.CloudDownloadManager.OFFLINE_AVAILABLE){result[0]=file;latch.countDown();}});});
            while(!latch.await(500,java.util.concurrent.TimeUnit.MILLISECONDS)){check(m);if(cancel.get())throw new InterruptedException();}check(m);if(result[0]==null)throw new IOException(error[0]);return result[0];
        }
        TLRPC.Message original=CenterSync.original(m);if(original==null)throw new IOException(CenterText.get("error"));FileLoader loader=FileLoader.getInstance(m.account);File file=loader.getPathToMessage(original);
        if(file!=null&&file.isFile()&&file.length()>0)return file;
        if(original.media instanceof TLRPC.TL_messageMediaPhoto){TLRPC.Photo photo=((TLRPC.TL_messageMediaPhoto)original.media).photo;TLRPC.PhotoSize size=photo==null?null:FileLoader.getClosestPhotoSizeWithSize(photo.sizes,1280);if(size==null)throw new IOException(CenterText.get("error"));loader.loadFile(ImageLocation.getForPhoto(size,photo),original,null,FileLoader.PRIORITY_NORMAL,1);}
        else if(original.media instanceof TLRPC.TL_messageMediaDocument){TLRPC.Document doc=((TLRPC.TL_messageMediaDocument)original.media).document;if(doc==null||doc.size>512L*1024*1024)throw new IOException(CenterText.get("size"));loader.loadFile(doc,original,FileLoader.PRIORITY_NORMAL,0);}else throw new IOException(CenterText.get("error"));
        long end=System.currentTimeMillis()+600000;while(System.currentTimeMillis()<end){if(cancel.get())throw new InterruptedException();check(m);file=loader.getPathToMessage(original);if(file!=null&&file.isFile()&&file.length()>0)return file;Thread.sleep(350);}throw new IOException(CenterText.get("error"));
    }
    public static Bitmap bitmap(File file)throws IOException{BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),bounds);if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IOException(CenterText.get("error"));int sample=1;while(Math.max(bounds.outWidth/sample,bounds.outHeight/sample)>2560)sample*=2;BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=sample;Bitmap b=BitmapFactory.decodeFile(file.getAbsolutePath(),options);if(b==null)throw new IOException(CenterText.get("error"));return b;}
    public static byte[] jpeg(Bitmap original)throws IOException{float scale=Math.min(1f,1280f/Math.max(original.getWidth(),original.getHeight()));Bitmap b=scale<1?Bitmap.createScaledBitmap(original,Math.max(1,(int)(original.getWidth()*scale)),Math.max(1,(int)(original.getHeight()*scale)),true):original;try{for(int q:new int[]{75,60,45,30}){ByteArrayOutputStream out=new ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.JPEG,q,out);if(out.size()<=850000)return out.toByteArray();}throw new IOException(CenterText.get("size"));}finally{if(b!=original)b.recycle();}}
    public static long perceptualHash(Bitmap b){Bitmap tiny=Bitmap.createScaledBitmap(b,9,8,true);long hash=0;try{for(int y=0;y<8;y++)for(int x=0;x<8;x++){int left=tiny.getPixel(x,y),right=tiny.getPixel(x+1,y);int l=Color.red(left)*3+Color.green(left)*6+Color.blue(left),r=Color.red(right)*3+Color.green(right)*6+Color.blue(right);hash=(hash<<1)|(l>r?1:0);}return hash;}finally{if(tiny!=b)tiny.recycle();}}
    private static synchronized File models()throws Exception{
        File root=new File(ApplicationLoader.applicationContext.getFilesDir(),"aic_ocr"),data=new File(root,"tessdata");if(!data.isDirectory()&&!data.mkdirs())throw new IOException("OCR storage");
        for(String language:new String[]{"eng","rus"}){File target=new File(data,language+".traineddata");if(target.length()>1000)continue;try(InputStream in=ApplicationLoader.applicationContext.getAssets().open("telepremium/ocr/"+language+".traineddata");OutputStream out=new FileOutputStream(target)){byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))>=0;)out.write(buffer,0,n);}}return root;
    }
    public static String ocr(Bitmap b)throws Exception{TessBaseAPI api=new TessBaseAPI();try{if(!api.init(models().getAbsolutePath(),"rus+eng"))throw new IOException(CenterText.get("ocr"));api.setImage(b);String text=api.getUTF8Text();return text==null?"":text;}finally{api.recycle();}}
    public static void preview(CenterData.Message m,Bitmap b,long time,int page)throws Exception{check(m);byte[] jpeg=jpeg(b);JSONObject o=new JSONObject().put("id",m.qualified()+":"+time+":"+page).put("message",m.qualified()).put("time",time).put("page",page).put("jpeg",Base64.encodeToString(jpeg,Base64.NO_WRAP)).put("created",System.currentTimeMillis());CenterStore.get().put(m.account,m.owner,"preview",o.optString("id"),o);Arrays.fill(jpeg,(byte)0);}
    public static void image(CenterData.Message m,File file,boolean recognize)throws Exception{Bitmap b=bitmap(file);try{check(m);m.hash=Long.toUnsignedString(perceptualHash(b),16);preview(m,b,0,0);if(!m.frameTimes.contains(0L))m.frameTimes.add(0L);if(recognize)m.ocr=ocr(b);}finally{b.recycle();}save(m);}
    public static void document(CenterData.Message m,File file,long cursor,AtomicBoolean cancel,Checkpoint checkpoint)throws Exception{
        if(m.kind.equals("pdf")){
            PDFBoxResourceLoader.init(ApplicationLoader.applicationContext);
            try(PDDocument pdf=PDDocument.load(file,com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())){
                m.pages=pdf.getNumberOfPages();PDFTextStripper stripper=new PDFTextStripper();
                try(ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);android.graphics.pdf.PdfRenderer renderer=new android.graphics.pdf.PdfRenderer(descriptor)){
                    for(int i=(int)cursor;i<m.pages;i++){if(cancel.get())throw new InterruptedException();check(m);stripper.setStartPage(i+1);stripper.setEndPage(i+1);String text=stripper.getText(pdf);
                        try(android.graphics.pdf.PdfRenderer.Page page=renderer.openPage(i)){int width=Math.min(1280,Math.max(1,page.getWidth()*2)),height=Math.max(1,Math.min(1800,(int)(page.getHeight()*(width/(float)Math.max(1,page.getWidth())))));Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);try{bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);preview(m,bitmap,0,i+1);if(text.trim().isEmpty())text=ocr(bitmap);}finally{bitmap.recycle();}}
                        m.pageEvidence.put(""+(i+1),text);m.ocr+="\n"+CenterText.get("page")+" "+(i+1)+"\n"+text;save(m);checkpoint.save(i+1,(i+1)*100/Math.max(1,m.pages));
                    }
                }
            }
        }else{
            if(!m.mime.startsWith("text/")&&!m.mime.contains("wordprocessingml")&&!m.mime.contains("opendocument.text")&&!m.mime.equals("application/rtf"))throw new IOException(CenterText.get("unsupported"));
            String text;
            if(m.mime.contains("wordprocessingml")||m.mime.contains("opendocument.text")){
                String wanted=m.mime.contains("wordprocessingml")?"word/document.xml":"content.xml";ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(java.util.zip.ZipInputStream zip=new java.util.zip.ZipInputStream(new FileInputStream(file))){for(java.util.zip.ZipEntry entry;(entry=zip.getNextEntry())!=null;){if(!wanted.equals(entry.getName()))continue;byte[] buffer=new byte[8192];for(int n;(n=zip.read(buffer))>=0;){if(bytes.size()+n>20*1024*1024)throw new IOException(CenterText.get("size"));bytes.write(buffer,0,n);}break;}}
                if(new String(bytes.toByteArray(),StandardCharsets.UTF_8).matches("(?is).*<!(DOCTYPE|ENTITY).*"))throw new SecurityException("DTD");org.xmlpull.v1.XmlPullParser parser=android.util.Xml.newPullParser();parser.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES,true);parser.setInput(new ByteArrayInputStream(bytes.toByteArray()),"UTF-8");StringBuilder out=new StringBuilder();for(int event=parser.getEventType();event!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT;event=parser.next()){if(event==org.xmlpull.v1.XmlPullParser.DOCDECL)throw new SecurityException("DTD");if(event==org.xmlpull.v1.XmlPullParser.TEXT)out.append(parser.getText()).append(' ');}text=out.toString();
            }else{if(file.length()>20L*1024*1024)throw new IOException(CenterText.get("size"));ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[8192];for(int n;(n=in.read(buffer))>=0;)out.write(buffer,0,n);}text=new String(out.toByteArray(),StandardCharsets.UTF_8);}
            m.ocr=text;save(m);checkpoint.save(1,100);
        }
    }
    public static void frames(CenterData.Message m,File file,long cursor,AtomicBoolean cancel,Checkpoint checkpoint)throws Exception{
        MediaMetadataRetriever retriever=new MediaMetadataRetriever();try{retriever.setDataSource(file.getAbsolutePath());m.duration=Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));if(m.duration>CenterIndex.settings().optInt("maxVideo",40)*60000L)throw new IOException(CenterText.get("maxVideo"));
            long lastHash=0;boolean first=cursor==0;for(long time=cursor;time<m.duration;time+=2000){if(cancel.get())throw new InterruptedException();check(m);Bitmap b=Build.VERSION.SDK_INT>=27?retriever.getScaledFrameAtTime(time*1000,MediaMetadataRetriever.OPTION_CLOSEST,640,360):retriever.getFrameAtTime(time*1000,MediaMetadataRetriever.OPTION_CLOSEST);if(b!=null)try{long hash=perceptualHash(b);if(first||Long.bitCount(hash^lastHash)>12){first=false;lastHash=hash;preview(m,b,time,0);if(!m.frameTimes.contains(time))m.frameTimes.add(time);String text=ocr(b);m.frameEvidence.put(""+time,text);if(!text.trim().isEmpty())m.ocr+="\n"+CenterTranscript.stamp(time)+" "+text;save(m);}}finally{b.recycle();}checkpoint.save(time+2000,(int)Math.min(100,100*(time+2000)/Math.max(1,m.duration)));}
        }finally{retriever.release();}
    }
    public static void describe(CenterData.Message m)throws Exception{describe(m,"");}
    public static void describe(CenterData.Message m,String question)throws Exception{check(m);List<JSONObject> frames=new ArrayList<>();if(m.pages>0){for(int page=1;page<=m.pages;page++)frames.add(new JSONObject().put("time",0).put("page",page));}else for(long time:m.frameTimes)frames.add(new JSONObject().put("time",time).put("page",0));List<byte[]> batch=new ArrayList<>();StringBuilder labels=new StringBuilder(),result=new StringBuilder();int number=0;
        for(JSONObject coordinates:frames){JSONObject frame=CenterStore.get().get(m.account,m.owner,"preview",m.qualified()+":"+coordinates.optLong("time")+":"+coordinates.optInt("page"));if(frame==null)continue;byte[] bytes=Base64.decode(frame.optString("jpeg"),Base64.NO_WRAP);batch.add(bytes);labels.append(CenterTranscript.stamp(frame.optLong("time"))).append(" / ").append(CenterText.get("page")).append(' ').append(frame.optInt("page")).append('\n');number++;if(batch.size()==5){check(m);result.append(CenterAi.vision("Describe visible content and readable text. Do not identify people. Distinguish observation and interpretation. Use the supplied timestamps and page numbers.",question+"\n"+labels+"\n"+m.text+"\n"+m.transcript,batch)).append('\n');for(byte[] b:batch)Arrays.fill(b,(byte)0);batch.clear();labels.setLength(0);}}
        if(!batch.isEmpty()){check(m);result.append(CenterAi.vision("Describe visible content. No face identification. Use supplied timestamps and page numbers.",question+"\n"+labels+"\n"+m.text+"\n"+m.transcript,batch));for(byte[] b:batch)Arrays.fill(b,(byte)0);}
        if(number==0)throw new IOException(CenterText.get("preview"));m.description=CenterReports.clean(result.toString());save(m);
    }
    public static void save(CenterData.Message m)throws Exception{check(m);CenterStore.get().put(m.account,m.owner,"message",m.qualified(),m.json());}
}
