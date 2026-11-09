package org.telegram.messenger.telepremium.cloud;

import android.graphics.*;
import androidx.exifinterface.media.ExifInterface;
import java.io.File;

/** Shared bounded decoder for verified photo plaintext, with orientation and density handling. */
public final class CloudImageDecoder {
    public static Bitmap decode(File file,int maxSide,long maxBytes)throws Exception{
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),options);
        if(options.outWidth<=0||options.outHeight<=0||maxSide<=0||maxBytes<4)throw new IllegalArgumentException("Unsupported image");
        int sample=1;
        while(true){long w=(options.outWidth+(long)sample-1)/sample,h=(options.outHeight+(long)sample-1)/sample;
            if(w<=maxSide&&h<=maxSide&&w<=maxBytes/(4*h))break;
            if(sample>=1<<29)throw new IllegalArgumentException("Image dimensions");sample*=2;
        }
        options.inJustDecodeBounds=false;options.inSampleSize=sample;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
        Bitmap result=BitmapFactory.decodeFile(file.getAbsolutePath(),options);if(result==null)throw new IllegalArgumentException("Image decoding failed");
        result.setDensity(Bitmap.DENSITY_NONE);
        try{ExifInterface exif=new ExifInterface(file.getAbsolutePath());Matrix transform=new Matrix();if(exif.isFlipped())transform.postScale(-1,1);transform.postRotate(exif.getRotationDegrees());
            if(!transform.isIdentity()){Bitmap oriented=Bitmap.createBitmap(result,0,0,result.getWidth(),result.getHeight(),transform,true);if(oriented!=result)result.recycle();result=oriented;result.setDensity(Bitmap.DENSITY_NONE);}
        }catch(java.io.IOException ignored){}
        return result;
    }
    private CloudImageDecoder(){}
}
