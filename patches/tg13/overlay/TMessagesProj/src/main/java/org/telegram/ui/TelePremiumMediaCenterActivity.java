package org.telegram.ui;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;

/** Standalone/deep-link host; the bottom Media tab embeds the same screen. */
public final class TelePremiumMediaCenterActivity extends Activity {
    private TelePremiumMediaCenterScreen screen;
    @Override protected void onCreate(Bundle state){super.onCreate(state);screen=new TelePremiumMediaCenterScreen(this,new TelePremiumMediaCenterScreen.Host(){
        public void close(){finish();}
        public void permissions(String[] p,int code){requestPermissions(p,code);}
        public void pick(Intent intent,int code){startActivityForResult(intent,code);}
    },false);screen.create(state);setContentView(screen.contentView);}
    @Override protected void onResume(){super.onResume();if(screen!=null)screen.resume();}
    @Override protected void onPause(){if(screen!=null)screen.pause();super.onPause();}
    @Override protected void onDestroy(){if(screen!=null)screen.destroy();super.onDestroy();}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(screen!=null)screen.onRequestPermissionsResult(code,permissions,results);}
    @Override protected void onActivityResult(int code,int result,Intent data){super.onActivityResult(code,result,data);if(screen!=null)screen.onActivityResult(code,result,data);}
}
