package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.telepremium.cloud.CloudNode;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import java.util.HashSet;

public class TelePremiumCloudFolderPickerActivity extends BaseFragment {
    public interface Callback { void onFolder(String id,String name); }
    private final int account; private final String folderId; private final String title; private final Callback callback;
    public TelePremiumCloudFolderPickerActivity(int account,String folderId,String title,Callback callback){this.account=account;this.folderId=folderId;this.title=title;this.callback=callback;}
    @Override public View createView(Context context){
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);actionBar.setTitle(title);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick(){@Override public void onItemClick(int id){if(id==-1)finishFragment();}});
        ScrollView s=new ScrollView(context);LinearLayout list=new LinearLayout(context);list.setOrientation(LinearLayout.VERTICAL);s.addView(list);fragmentView=s;
        TelePremiumCloudManager cloud=TelePremiumCloudManager.getInstance(account);
        addTarget(context,list,folderId,"root".equals(folderId)?"Корень облака":title,0);
        addFolderTargets(context,list,cloud,folderId,1,new HashSet<>());
        return fragmentView;
    }

    private void addFolderTargets(Context context,LinearLayout list,TelePremiumCloudManager cloud,String parentId,int depth,HashSet<String> visited){
        if(!visited.add(parentId))return;
        for(CloudNode node:cloud.children(parentId,false))if(node.type==CloudNode.TYPE_FOLDER){
            addTarget(context,list,node.id,node.name,depth);
            addFolderTargets(context,list,cloud,node.id,depth+1,visited);
        }
    }

    private void addTarget(Context context,LinearLayout list,String id,String name,int depth){
        TextCell cell=new TextCell(context,getResourceProvider());
        StringBuilder prefix=new StringBuilder();for(int i=0;i<depth;i++)prefix.append("  ");
        cell.setTextAndValue(prefix+(depth==0?"☁  ":"📁  ")+name,"Переместить сюда",true);
        cell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        cell.setOnClickListener(v->{callback.onFolder(id,name);finishFragment();});
        list.addView(cell,new LinearLayout.LayoutParams(-1,AndroidUtilities.dp(60)));
    }
}

