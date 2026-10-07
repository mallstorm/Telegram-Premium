package org.telegram.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.telepremium.cloud.CloudNode;
import org.telegram.messenger.telepremium.cloud.TelePremiumCloudManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;

import java.util.List;

public class TelePremiumCloudActivity extends BaseFragment {
    private final int account;
    private final String folderId;
    private final String folderName;
    private LinearLayout content;
    private TelePremiumCloudManager cloud;

    public TelePremiumCloudActivity(int account) { this(account, "root", "Облако"); }
    private TelePremiumCloudActivity(int account, String folderId, String folderName) {
        this.account=account; this.folderId=folderId; this.folderName=folderName;
    }

    @Override public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(folderName);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick(){@Override public void onItemClick(int id){if(id==-1)finishFragment();}});
        ScrollView scroll=new ScrollView(context);
        content=new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        fragmentView=scroll;
        cloud=TelePremiumCloudManager.getInstance(account);
        showStatus(context, "Синхронизация облака…");
        cloud.bootstrap(error -> {
            if(error!=null) showStatus(context, "Не удалось синхронизировать: "+error);
            else render(context);
        });
        return fragmentView;
    }

    private void render(Context context) {
        content.removeAllViews();
        TextCell create=new TextCell(context, getResourceProvider());
        create.setTextAndValue("＋ Новая папка", "Создать каталог в TelePremium Cloud", true);
        create.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        create.setOnClickListener(v -> createFolder(context));
        content.addView(create, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(58)));

        List<CloudNode> nodes=cloud.children(folderId, false);
        if(nodes.isEmpty()) {
            showStatus(context, "Папка пуста\nСтруктура хранится в Telegram Cloud и восстановится после переустановки.");
            return;
        }
        for(CloudNode node:nodes) {
            TextCell cell=new TextCell(context, getResourceProvider());
            String value=node.type==CloudNode.TYPE_FOLDER ? "Папка · ☁" : humanSize(node.size)+" · ☁";
            cell.setTextAndValue((node.type==CloudNode.TYPE_FOLDER ? "📁 " : "📄 ")+node.name, value, true);
            cell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
            cell.setOnClickListener(v -> {
                if(node.type==CloudNode.TYPE_FOLDER) presentFragment(new TelePremiumCloudActivity(account,node.id,node.name));
            });
            cell.setOnLongClickListener(v -> { showNodeMenu(context,node); return true; });
            content.addView(cell,new LinearLayout.LayoutParams(-1,AndroidUtilities.dp(58)));
        }
    }

    private void createFolder(Context context) {
        EditText input=new EditText(context); input.setHint("Имя папки"); input.setSingleLine(true);
        new AlertDialog.Builder(context).setTitle("Новая папка").setView(input)
                .setPositiveButton("Создать",(d,w)-> {
                    String name=input.getText().toString().trim();
                    if(!name.isEmpty()) cloud.createFolder(folderId,name,error->render(context));
                }).setNegativeButton("Отмена",null).show();
    }

    private void showNodeMenu(Context context, CloudNode node) {
        CharSequence[] items=new CharSequence[]{"Переименовать",node.favorite?"Убрать из избранного":"В избранное","В корзину"};
        new AlertDialog.Builder(context).setItems(items,(d,which)-> {
            if(which==0) rename(context,node);
            else if(which==1) cloud.toggleFavorite(node,error->render(context));
            else cloud.trash(node,error->render(context));
        }).show();
    }

    private void rename(Context context, CloudNode node) {
        EditText input=new EditText(context); input.setText(node.name); input.setSingleLine(true);
        new AlertDialog.Builder(context).setTitle("Переименовать").setView(input)
                .setPositiveButton("Сохранить",(d,w)-> {
                    String name=input.getText().toString().trim();
                    if(!name.isEmpty()) cloud.rename(node,name,error->render(context));
                }).setNegativeButton("Отмена",null).show();
    }

    private void showStatus(Context context,String text) {
        content.removeAllViews();
        TextView v=new TextView(context); v.setText(text); v.setTextSize(15); v.setPadding(AndroidUtilities.dp(21),AndroidUtilities.dp(28),AndroidUtilities.dp(21),AndroidUtilities.dp(28));
        v.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText)); content.addView(v);
    }

    private String humanSize(long n) {
        if(n<1024)return n+" B"; if(n<1024*1024)return (n/1024)+" KB"; if(n<1024L*1024*1024)return (n/(1024*1024))+" MB"; return String.format("%.1f GB",n/(1024d*1024d*1024d));
    }
}
