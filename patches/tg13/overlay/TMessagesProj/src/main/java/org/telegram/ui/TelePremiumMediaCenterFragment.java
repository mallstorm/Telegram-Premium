package org.telegram.ui;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import org.telegram.ui.ActionBar.BaseFragment;

/** Real bottom-tab content, sharing the standalone screen and playback controller. */
public final class TelePremiumMediaCenterFragment extends BaseFragment {
    private TelePremiumMediaCenterScreen screen;
    private final Runnable backToChats;
    public TelePremiumMediaCenterFragment(Runnable backToChats){this.backToChats=backToChats;}
    @Override public View createView(Context context){
        actionBar.setVisibility(View.GONE);
        screen=new TelePremiumMediaCenterScreen(getParentActivity(),new TelePremiumMediaCenterScreen.Host(){
            public void close(){backToChats.run();}
            public void permissions(String[] permissions,int code){getParentActivity().requestPermissions(permissions,code);}
            public void pick(Intent intent,int code){startActivityForResult(intent,code);}
        },true);screen.create(null);fragmentView=screen.contentView;return fragmentView;
    }
    @Override public void onResume(){super.onResume();if(screen!=null)screen.resume();}
    @Override public void onPause(){if(screen!=null)screen.pause();super.onPause();}
    @Override public void onFragmentDestroy(){if(screen!=null)screen.destroy();super.onFragmentDestroy();}
    @Override public void onActivityResultFragment(int code,int result,Intent data){if(screen!=null)screen.onActivityResult(code,result,data);}
    @Override public void onRequestPermissionsResultFragment(int code,String[] permissions,int[] results){if(screen!=null)screen.onRequestPermissionsResult(code,permissions,results);}
}
