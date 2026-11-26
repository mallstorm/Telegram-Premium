package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.telepremium.TPStrings;
import org.telegram.messenger.telepremium.TelePremiumGhost;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;

/** Invisible mode switches for one account. */
public final class TelePremiumGhostActivity extends BaseFragment {
    private final int account;
    public TelePremiumGhostActivity(int account) { this.account = account; }
    private String t(String en, String ru) { return TPStrings.isRussian() ? ru : en; }

    @Override public View createView(Context context) {
        actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back);
        actionBar.setTitle(t("Invisible mode", "Невидимка"));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        ScrollView scroll = new ScrollView(context);
        scroll.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(24));
        scroll.addView(content);
        String name = UserObject.getUserName(UserConfig.getInstance(account).getCurrentUser());
        label(content, context, t("Account: ", "Аккаунт: ") + name);
        toggle(content, context, t("Hide online status", "Не показывать «в сети»"), TelePremiumGhost.ONLINE);
        toggle(content, context, t("Do not mark as read", "Не отмечать прочитанным"), TelePremiumGhost.READ);
        toggle(content, context, t("Hide «typing…»", "Скрывать «печатает…»"), TelePremiumGhost.TYPING);
        label(content, context, t(
                "Messages stay unread for the sender: read receipts, voice/video listening marks and story views are not sent. Your own unread counters may return after the chat list is refreshed from the server. Sending a message to a chat still marks it read on Telegram's side.",
                "Собеседник видит сообщения непрочитанными: отметки о прочтении, прослушивании голосовых/кружков и просмотре историй не отправляются. Ваши счётчики непрочитанных могут вернуться после обновления списка чатов с сервера. Отправка сообщения в чат всё равно помечает его прочитанным на стороне Telegram."));
        fragmentView = scroll;
        return scroll;
    }

    private void toggle(LinearLayout parent, Context context, String title, int flag) {
        TextCheckCell cell = new TextCheckCell(context);
        cell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        cell.setTextAndCheck(title, TelePremiumGhost.has(account, flag), true);
        cell.setOnClickListener(v -> {
            boolean next = !cell.isChecked();
            TelePremiumGhost.set(account, flag, next);
            cell.setChecked(next);
        });
        parent.addView(cell, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(56)));
    }

    private void label(LinearLayout parent, Context context, String text) {
        TextView v = new TextView(context);
        v.setText(text);
        v.setTextSize(14);
        v.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        v.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(14), AndroidUtilities.dp(20), AndroidUtilities.dp(12));
        parent.addView(v);
    }
}
