package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.telepremium.cloud.CloudIntegrityChecker;
import org.telegram.messenger.telepremium.cloud.CloudSnapshot;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.TextCell;

public class TelePremiumCloudDiagnosticsActivity extends BaseFragment {
    private final int account;
    public TelePremiumCloudDiagnosticsActivity(int account){this.account=account;}
    @Override public View createView(Context context){
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);actionBar.setTitle(org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_166", org.telegram.messenger.R.string.TP_L10N_166));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick(){@Override public void onItemClick(int id){if(id==-1)finishFragment();}});
        ScrollView s=new ScrollView(context);LinearLayout list=new LinearLayout(context);list.setOrientation(LinearLayout.VERTICAL);s.addView(list);fragmentView=s;
        TelePremiumCloudManager cloud=TelePremiumCloudManager.getInstance(account);
        cloud.bootstrap(error->{
            list.removeAllViews();CloudSnapshot snap=cloud.getSnapshot();CloudIntegrityChecker.Result r=cloud.checkIntegrity();
            add(list,context,org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_167", org.telegram.messenger.R.string.TP_L10N_167),error==null?(r.ok()?org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_168", org.telegram.messenger.R.string.TP_L10N_168):org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_169", org.telegram.messenger.R.string.TP_L10N_169)):error);
            add(list,context,"Cloud ID",snap==null?"—":snap.cloudId);
            add(list,context,org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_170", org.telegram.messenger.R.string.TP_L10N_170),snap==null?"—":String.valueOf(snap.generation));
            add(list,context,org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_171", org.telegram.messenger.R.string.TP_L10N_171),String.valueOf(r.nodes));
            add(list,context,org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_172", org.telegram.messenger.R.string.TP_L10N_172),String.valueOf(r.errors));
            add(list,context,org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_173", org.telegram.messenger.R.string.TP_L10N_173),String.valueOf(r.orphanNodes));
            add(list,context,org.telegram.messenger.telepremium.TPStrings.get("TP_L10N_174", org.telegram.messenger.R.string.TP_L10N_174),String.valueOf(r.cycles));
        });return fragmentView;
    }
    private void add(LinearLayout list,Context context,String name,String value){TextCell c=new TextCell(context,getResourceProvider());c.setTextAndValue(name,value,true);list.addView(c,new LinearLayout.LayoutParams(-1,AndroidUtilities.dp(58)));}
}
