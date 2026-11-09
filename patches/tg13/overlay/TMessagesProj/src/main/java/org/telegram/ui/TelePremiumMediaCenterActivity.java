package org.telegram.ui;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;

/** Fullscreen host opened by the Media menu entry and deep links. */
public final class TelePremiumMediaCenterActivity extends Activity {
    private TelePremiumMediaCenterScreen screen;
    @Override protected void onCreate(Bundle state){super.onCreate(state);screen=new TelePremiumMediaCenterScreen(this,new TelePremiumMediaCenterScreen.Host(){
        public void close(){finish();}
        public void permissions(String[] p,int code){requestPermissions(p,code);}
        public void pick(Intent intent,int code){startActivityForResult(intent,code);}
    },false);screen.create(state);
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        final android.view.View root=screen.contentView;
        final int left=root.getPaddingLeft(),top=root.getPaddingTop(),right=root.getPaddingRight(),bottom=root.getPaddingBottom();
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root,(view,insets)->{
            androidx.core.graphics.Insets safe=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()|androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            int keyboard=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom;
            view.setPadding(left+safe.left,top+safe.top,right+safe.right,bottom+Math.max(safe.bottom,keyboard));
            return androidx.core.view.WindowInsetsCompat.CONSUMED;
        });
        setContentView(root);androidx.core.view.ViewCompat.requestApplyInsets(root);
    }
    @Override protected void onResume(){super.onResume();if(screen!=null)screen.resume();}
    @Override protected void onPause(){if(screen!=null)screen.pause();super.onPause();}
    @Override protected void onDestroy(){if(screen!=null)screen.destroy();super.onDestroy();}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(screen!=null)screen.onRequestPermissionsResult(code,permissions,results);}
    @Override protected void onActivityResult(int code,int result,Intent data){super.onActivityResult(code,result,data);if(screen!=null)screen.onActivityResult(code,result,data);}
}
