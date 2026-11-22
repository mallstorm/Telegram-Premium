package org.telegram.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.text.TextPaint;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.telepremium.ai.TPAiStore;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Full-height, chat-first companion to the Analyzer workspace.
 * This screen intentionally does NOT reuse TelePremiumSettingsUi note() (13sp)
 * or global density overrides; it has independent readable chat typography.
 * The selected account, group, consent and message thread belong to the source workspace.
 */
public final class TelePremiumAnalyzerChatActivity extends BaseFragment {
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*", Pattern.DOTALL);
    private static final Pattern EVIDENCE = Pattern.compile("\\[msg:(\\d+)\\]");
    private final TelePremiumAnalyzerActivity workspace;
    private ScrollView scroll;
    private LinearLayout messages;
    private EditText composer;
    private TextView status;
    private TextView send;
    private int renderedCount = -1;

    public TelePremiumAnalyzerChatActivity(TelePremiumAnalyzerActivity workspace) {
        super();
        this.workspace = workspace;
    }

    private int dp(float value) {
        return AndroidUtilities.dp(value);
    }

    private int themed(int key) {
        return getThemedColor(key);
    }

    private TextView text(Context context, String value, int sizeSp, int color, boolean strong) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        if (strong) view.setTypeface(AndroidUtilities.bold());
        view.setIncludeFontPadding(true);
        view.setLineSpacing(dp(3), 1.08f);
        return view;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("AI-чат");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(themed(Theme.key_windowBackgroundGray));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(11), dp(16), dp(11));
        header.setBackgroundColor(themed(Theme.key_windowBackgroundWhite));
        TextView group = text(context, workspace.chatGroupTitle(), 16,
                themed(Theme.key_windowBackgroundWhiteBlackText), true);
        group.setSingleLine(true);
        group.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(group);
        TextView meta = text(context, workspace.chatContextDescription(), 13,
                themed(Theme.key_windowBackgroundWhiteGrayText), false);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(meta);
        header.setOnClickListener(v -> finishFragment());
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        messages = new LinearLayout(context);
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPadding(dp(13), dp(15), dp(13), dp(18));
        scroll.addView(messages, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout bottom = new LinearLayout(context);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(10), dp(8), dp(10), dp(10));
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setBackgroundColor(themed(Theme.key_windowBackgroundWhite));

        composer = new EditText(context);
        composer.setSingleLine(false);
        composer.setMinLines(1);
        composer.setMaxLines(4);
        composer.setTextSize(16);
        composer.setTextColor(themed(Theme.key_windowBackgroundWhiteBlackText));
        composer.setHintTextColor(themed(Theme.key_windowBackgroundWhiteGrayText));
        composer.setHint("Сообщение AI...");
        composer.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        composer.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        composer.setPadding(dp(13), dp(7), dp(12), dp(7));
        composer.setBackground(round(themed(Theme.key_windowBackgroundGray), 21));
        composer.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEND) {
                submit();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, -2, 1f);
        inputLp.rightMargin = dp(9);
        bottom.addView(composer, inputLp);

        send = text(context, "↑", 25, 0xffffffff, true);
        send.setGravity(Gravity.CENTER);
        send.setBackground(round(themed(Theme.key_featuredStickers_addButton), 22));
        send.setContentDescription("Отправить сообщение AI");
        send.setOnClickListener(v -> submit());
        bottom.addView(send, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout bottomWithStatus = new LinearLayout(context);
        bottomWithStatus.setOrientation(LinearLayout.VERTICAL);
        bottomWithStatus.setBackgroundColor(themed(Theme.key_windowBackgroundWhite));
        status = text(context, "", 12, themed(Theme.key_windowBackgroundWhiteGrayText), false);
        status.setPadding(dp(16), 0, dp(12), 0);
        bottomWithStatus.addView(status);
        bottomWithStatus.addView(bottom);
        root.addView(bottomWithStatus, new LinearLayout.LayoutParams(-1, -2));

        fragmentView = root;
        workspace.onChatOpened(this);
        refresh();
        return fragmentView;
    }

    private GradientDrawable round(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private void submit() {
        String question = composer.getText().toString().trim();
        if (TextUtils.isEmpty(question)) return;
        if (workspace.chatIsBusy()) {
            Toast.makeText(getParentActivity(), "Дождитесь завершения анализа", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!workspace.chatCanAsk()) {
            Toast.makeText(getParentActivity(), "Разрешите AI-обработку в настройках группы",
                    Toast.LENGTH_LONG).show();
            return;
        }
        composer.setText("");
        workspace.askFromChat(question);
    }

    /** Rebuild only message history, never the editor. Preserves the keyboard and draft. */
    void refresh() {
        if (messages == null || scroll == null) return;
        Context context = messages.getContext();
        List<String[]> turns = workspace.chatTurns();
        int oldScroll = scroll.getScrollY();
        boolean jumpToBottom = turns.size() != renderedCount;
        renderedCount = turns.size();
        messages.removeAllViews();

        if (turns.isEmpty()) {
            TextView empty = text(context,
                    "Задай вопрос по публикациям группы.\n\n" +
                    "Например: «Кто чаще всего выкладывал графики EUR/USD " +
                    "и какие выводы можно сделать по его сообщениям?»",
                    17, themed(Theme.key_windowBackgroundWhiteBlackText), false);
            empty.setPadding(dp(8), dp(20), dp(8), dp(16));
            messages.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        } else {
            for (String[] turn : turns) {
                addMessage(context, turn[0], true);
                addMessage(context, turn[1], false);
            }
        }
        status.setText(workspace.chatIsBusy() ? "AI анализирует сообщения…" :
                "Материалы выбранной группы · ответы с источниками");
        send.setAlpha(workspace.chatIsBusy() ? 0.5f : 1f);
        scroll.post(() -> {
            if (jumpToBottom) scroll.fullScroll(View.FOCUS_DOWN);
            else scroll.scrollTo(0, oldScroll);
        });
    }

    private void addMessage(Context context, String value, boolean outgoing) {
        LinearLayout.LayoutParams outer = new LinearLayout.LayoutParams(-1, -2);
        outer.bottomMargin = dp(outgoing ? 9 : 20);
        FrameLayout line = new FrameLayout(context);
        messages.addView(line, outer);

        TextView bubble = text(context, "", outgoing ? 16 : 17,
                themed(Theme.key_windowBackgroundWhiteBlackText), false);
        bubble.setText(outgoing ? value : formatAnswer(value));
        bubble.setTextIsSelectable(!outgoing);
        bubble.setPadding(dp(14), dp(11), dp(14), dp(12));
        int color = outgoing ? themed(Theme.key_chat_outBubble) :
                themed(Theme.key_windowBackgroundWhite);
        bubble.setBackground(round(color, 17));
        bubble.setMaxWidth(dp(outgoing ? 310 : 430));
        if (!outgoing) {
            bubble.setMovementMethod(LinkMovementMethod.getInstance());
            bubble.setHighlightColor(0x22aaaaaa);
            bubble.setOnLongClickListener(v -> {
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Ответ AI", value));
                    Toast.makeText(context, "Ответ скопирован", Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2,
                outgoing ? Gravity.RIGHT : Gravity.LEFT);
        line.addView(bubble, lp);
    }

    private SpannableStringBuilder formatAnswer(String markdown) {
        String source = markdown == null ? "" : markdown;
        source = source.replaceAll("(?m)^#{1,6}\\s+", "");
        Matcher bold = BOLD.matcher(source);
        SpannableStringBuilder formatted = new SpannableStringBuilder();
        int cursor = 0;
        while (bold.find()) {
            formatted.append(source, cursor, bold.start());
            int start = formatted.length();
            formatted.append(bold.group(1));
            formatted.setSpan(new StyleSpan(Typeface.BOLD), start, formatted.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            cursor = bold.end();
        }
        formatted.append(source, cursor, source.length());
        Matcher citation = EVIDENCE.matcher(formatted.toString());
        while (citation.find()) {
            final int messageId;
            try {
                messageId = Integer.parseInt(citation.group(1));
            } catch (NumberFormatException ignored) {
                continue;
            }
            formatted.setSpan(new ClickableSpan() {
                @Override public void onClick(View widget) {
                    workspace.openEvidenceById(messageId);
                }
                @Override public void updateDrawState(TextPaint paint) {
                    paint.setColor(themed(Theme.key_featuredStickers_addButton));
                    paint.setUnderlineText(true);
                }
            }, citation.start(), citation.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return formatted;
    }

    @Override
    public void onFragmentDestroy() {
        workspace.onChatClosed(this);
        super.onFragmentDestroy();
    }
}
