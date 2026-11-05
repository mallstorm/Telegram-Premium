package org.telegram.ui.iv;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.Html;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.CharacterStyle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.RichMessageLayout;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.telepremium.TPStrings;
import org.telegram.messenger.telepremium.ai.TPAiClient;
import org.telegram.messenger.telepremium.ai.TPAiStore;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_iv;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.QuoteSpan;
import org.telegram.ui.Components.TextStyleSpan;
import org.telegram.ui.Components.URLSpanReplacement;
import org.telegram.utils.code.highlight.CodeHighlighting;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * TelePremium's own path for the article editor when Telegram does not allow posting rich
 * messages (no Premium): the editor stays fully usable and the article goes out as
 * <ul>
 * <li>pictures rendered from the article layout itself (tables and formulas included),</li>
 * <li>text with Telegram formatting (tables become monospace grids),</li>
 * <li>a Telegraph page that opens as Instant View for everyone.</li>
 * </ul>
 * It also hosts the AI assistant that works through the user's own AI connections.
 */
public final class TPRichTools {

    private TPRichTools() {}

    static String L(String ru, String en) { return TPStrings.isRussian() ? ru : en; }

    /** Telegram itself refuses rich posting for this account. */
    static boolean nativeDisallowed(int account) {
        return !MessagesController.getInstance(account).richEditorAllowed();
    }

    // ================================================================== sending

    public interface SendTarget {
        int account();
        RichEditorListView list();
        /** A view attached to the window that can temporarily host the off-screen renderer. */
        ViewGroup renderHost();
        void sendText(CharSequence text);
        void sendPhotos(ArrayList<String> paths);
        void finish();
    }

    static void showSendOptions(Context context, Theme.ResourcesProvider rp, SendTarget target) {
        CharSequence[] items = {
                L("Картинкой — ровно как свёрстано", "As pictures — exactly as laid out"),
                L("Текстом — форматирование Telegram", "As text — Telegram formatting"),
                L("Статьёй Telegraph — ссылка с Instant View", "As a Telegraph article — Instant View link")
        };
        int[] icons = {R.drawable.tp_ic_palette, R.drawable.tp_ic_text_size, R.drawable.tp_ic_link};
        new AlertDialog.Builder(context, rp)
                .setTitle(L("Как отправить статью?", "How to send the article?"))
                .setItems(items, icons, (d, which) -> {
                    if (which == 0) sendAsImages(context, rp, target);
                    else if (which == 1) sendAsText(target);
                    else sendAsTelegraph(context, rp, target);
                })
                .show();
    }

    private static void sendAsText(SendTarget target) {
        CharSequence text = toTelegramText(target.list().rows);
        if (TextUtils.isEmpty(text)) return;
        target.sendText(text);
        target.finish();
    }

    private static void sendAsImages(Context context, Theme.ResourcesProvider rp, SendTarget target) {
        TL_iv.RichMessage rich = target.list().buildDraftRichMessage();
        if (rich == null) return;
        AlertDialog progress = new AlertDialog(context, AlertDialog.ALERT_TYPE_SPINNER, rp);
        progress.setCanCancel(false);
        progress.show();
        renderImages(context, target.account(), rp, rich, target.renderHost(), paths -> {
            try { progress.dismiss(); } catch (Exception ignore) {}
            if (paths == null || paths.isEmpty()) {
                Toast.makeText(context, L("Не удалось отрисовать статью", "Could not render the article"), Toast.LENGTH_LONG).show();
                return;
            }
            target.sendPhotos(paths);
            target.finish();
        });
    }

    private static void sendAsTelegraph(Context context, Theme.ResourcesProvider rp, SendTarget target) {
        List<BlockRow> rows = target.list().rows;
        String title = titleOf(rows);
        JSONArray content = toTelegraphNodes(rows, title);
        TLRPC.User me = UserConfig.getInstance(target.account()).getCurrentUser();
        String author = me == null ? "TelePremium" : UserObject.getUserName(me);
        AlertDialog progress = new AlertDialog(context, AlertDialog.ALERT_TYPE_SPINNER, rp);
        progress.setCanCancel(false);
        progress.show();
        TPAiClient.async(() -> publishTelegraph(title, author, content), (url, error) -> {
            try { progress.dismiss(); } catch (Exception ignore) {}
            if (error != null || TextUtils.isEmpty(url)) {
                new AlertDialog.Builder(context, rp).setTitle(L("Telegraph недоступен", "Telegraph unavailable"))
                        .setMessage(error == null ? "" : error).setPositiveButton("OK", null).show();
                return;
            }
            target.sendText(title + "\n" + url);
            target.finish();
        });
    }

    // ================================================================== text

    /** Article → message text with Telegram entities. Nothing is silently dropped. */
    static CharSequence toTelegramText(List<BlockRow> rows) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        boolean prevList = false;
        int i = 0;
        while (rows != null && i < rows.size()) {
            BlockRow r = rows.get(i);
            if (r.detailsEnd || r.block == null) { i++; continue; }
            boolean isList = r.level > 0;
            if (out.length() > 0) out.append(prevList && isList ? "\n" : "\n\n");
            prevList = isList;
            if (!r.quoteIds.isEmpty()) {
                long qid = r.quoteIds.get(0);
                int start = out.length();
                int j = i;
                while (j < rows.size() && !rows.get(j).quoteIds.isEmpty() && rows.get(j).quoteIds.get(0) == qid) {
                    if (j > i) out.append('\n');
                    appendLeaf(out, rows.get(j));
                    j++;
                }
                if (out.length() > start) QuoteSpan.putQuote(out, start, out.length(), false);
                i = j;
                continue;
            }
            appendLeaf(out, r);
            i++;
        }
        int n = out.length();
        RichTextStyle.setStyle(out, 0, n, RichTextStyle.MARKED, false);
        RichTextStyle.setStyle(out, 0, n, RichTextStyle.SUBSCRIPT, false);
        RichTextStyle.setStyle(out, 0, n, RichTextStyle.SUPERSCRIPT, false);
        replaceMath(out);
        return out;
    }

    private static void appendLeaf(SpannableStringBuilder out, BlockRow r) {
        TL_iv.PageBlock b = r.block;
        if (b instanceof TL_iv.pageBlockTable) {
            TL_iv.pageBlockTable t = (TL_iv.pageBlockTable) b;
            String title = t.title == null ? "" : RichTextStyle.plainOf(t.title);
            if (!TextUtils.isEmpty(title)) {
                int s = out.length();
                out.append(title).append('\n');
                RichTextStyle.setStyle(out, s, s + title.length(), RichTextStyle.BOLD, true);
            }
            appendPre(out, tableGrid(t), null);
            return;
        }
        if (b instanceof TL_iv.pageBlockMath) {
            appendPre(out, ((TL_iv.pageBlockMath) b).source, "latex");
            return;
        }
        if (b instanceof TL_iv.pageBlockDivider) {
            out.append("———");
            return;
        }
        if (RichEditorListView.isDetailsHeader(r)) {
            int s = out.length();
            out.append("▸ ").append(RichTextStyle.toSimpleSpannable(((TL_iv.pageBlockDetails) b).title, b));
            RichTextStyle.setStyle(out, s, out.length(), RichTextStyle.BOLD, true);
            return;
        }
        if (b instanceof TL_iv.pageBlockPreformatted) {
            appendPre(out, RichTextStyle.plainOf(b.text), ((TL_iv.pageBlockPreformatted) b).language);
            return;
        }
        CharSequence text = RichTextStyle.toSimpleSpannable(b.text, b);
        if (text == null) text = "";
        if (text.length() == 0 && !(b instanceof TL_iv.pageBlockParagraph)) {
            out.append(L("[вложение]", "[attachment]"));
            return;
        }
        if (r.level > 0) {
            for (int k = 1; k < r.level; k++) out.append("    ");
            out.append(r.checkbox ? (r.checked ? "☑ " : "☐ ") : r.num > 0 ? r.num + ". " : "• ");
        }
        int start = out.length();
        out.append(text);
        if (RichEditorListView.isHeading(b)) RichTextStyle.setStyle(out, start, out.length(), RichTextStyle.BOLD, true);
        if (b instanceof TL_iv.pageBlockFooter) RichTextStyle.setStyle(out, start, out.length(), RichTextStyle.ITALIC, true);
        if (b instanceof TL_iv.pageBlockBlockquote || b instanceof TL_iv.pageBlockPullquote) {
            if (out.length() > start) QuoteSpan.putQuote(out, start, out.length(), false);
        }
    }

    private static void appendPre(SpannableStringBuilder out, String code, String language) {
        if (TextUtils.isEmpty(code)) return;
        int s = out.length();
        out.append(code);
        out.setSpan(new CodeHighlighting.Span(true, 0, null, language, code), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static void replaceMath(SpannableStringBuilder sb) {
        MathSpan[] spans = sb.getSpans(0, sb.length(), MathSpan.class);
        for (int i = spans.length - 1; i >= 0; i--) {
            MathSpan m = spans[i];
            int s = sb.getSpanStart(m), e = sb.getSpanEnd(m);
            sb.removeSpan(m);
            if (s < 0 || e < s || TextUtils.isEmpty(m.source)) continue;
            sb.replace(s, e, m.source);
            RichTextStyle.setStyle(sb, s, s + m.source.length(), RichTextStyle.MONO, true);
        }
    }

    /** Monospace grid: columns padded, header separated, long cells shortened. */
    static String tableGrid(TL_iv.pageBlockTable t) {
        ArrayList<String[]> cells = new ArrayList<>();
        int cols = 0;
        for (TL_iv.pageTableRow row : t.rows) {
            String[] r = new String[row.cells.size()];
            for (int c = 0; c < r.length; c++) {
                TL_iv.pageTableCell cell = row.cells.get(c);
                String v = cell.text == null ? "" : RichTextStyle.plainOf(cell.text).replace('\n', ' ').trim();
                if (v.length() > 28) v = v.substring(0, 27) + "…";
                r[c] = v;
            }
            cols = Math.max(cols, r.length);
            cells.add(r);
        }
        int[] width = new int[cols];
        for (String[] r : cells) for (int c = 0; c < r.length; c++) width[c] = Math.max(width[c], r[c].length());
        StringBuilder sb = new StringBuilder();
        for (int ri = 0; ri < cells.size(); ri++) {
            String[] r = cells.get(ri);
            for (int c = 0; c < cols; c++) {
                String v = c < r.length ? r[c] : "";
                if (c > 0) sb.append(" │ ");
                sb.append(v);
                for (int p = v.length(); p < width[c]; p++) sb.append(' ');
            }
            sb.append('\n');
            boolean header = ri == 0 && !t.rows.isEmpty() && !t.rows.get(0).cells.isEmpty() && t.rows.get(0).cells.get(0).header;
            if (header || (ri == 0 && cells.size() > 1)) {
                for (int c = 0; c < cols; c++) {
                    if (c > 0) sb.append("─┼─");
                    for (int p = 0; p < width[c]; p++) sb.append('─');
                }
                sb.append('\n');
            }
        }
        return sb.toString().replaceAll("\\s+$", "");
    }

    // ================================================================== Telegraph

    static String titleOf(List<BlockRow> rows) {
        for (BlockRow r : rows) {
            if (r.block == null || r.detailsEnd) continue;
            String plain = r.block.text == null ? "" : RichTextStyle.plainOf(r.block.text).trim();
            if (plain.isEmpty()) continue;
            if (plain.length() > 120) plain = plain.substring(0, 119) + "…";
            return plain;
        }
        return L("Статья", "Article");
    }

    static JSONArray toTelegraphNodes(List<BlockRow> rows, String title) {
        JSONArray out = new JSONArray();
        try {
            boolean titleSkipped = false;
            JSONObject list = null;
            boolean listOrdered = false;
            for (int i = 0; i < rows.size(); i++) {
                BlockRow r = rows.get(i);
                TL_iv.PageBlock b = r.block;
                if (b == null || r.detailsEnd) continue;
                if (!titleSkipped && RichEditorListView.isHeading(b) && title.equals(RichTextStyle.plainOf(b.text).trim())) {
                    titleSkipped = true;
                    continue;
                }
                if (r.level > 0 && !(b instanceof TL_iv.pageBlockTable) && !(b instanceof TL_iv.pageBlockMath)) {
                    boolean ordered = r.num > 0 && !r.checkbox;
                    if (list == null || listOrdered != ordered) {
                        list = node(ordered ? "ol" : "ul");
                        listOrdered = ordered;
                        out.put(list);
                    }
                    JSONArray children = new JSONArray();
                    StringBuilder prefix = new StringBuilder();
                    for (int k = 1; k < r.level; k++) prefix.append("    ");
                    if (r.checkbox) prefix.append(r.checked ? "☑ " : "☐ ");
                    if (prefix.length() > 0) children.put(prefix.toString());
                    inline(children, RichTextStyle.toSpannable(b.text, b));
                    list.getJSONArray("children").put(node("li").put("children", children));
                    continue;
                }
                list = null;
                if (b instanceof TL_iv.pageBlockTable) {
                    TL_iv.pageBlockTable t = (TL_iv.pageBlockTable) b;
                    if (t.title != null && !RichTextStyle.plainOf(t.title).isEmpty()) {
                        out.put(node("p").put("children", new JSONArray().put(node("strong").put("children", new JSONArray().put(RichTextStyle.plainOf(t.title))))));
                    }
                    out.put(node("pre").put("children", new JSONArray().put(tableGrid(t))));
                } else if (b instanceof TL_iv.pageBlockMath) {
                    out.put(node("pre").put("children", new JSONArray().put(((TL_iv.pageBlockMath) b).source)));
                } else if (b instanceof TL_iv.pageBlockDivider) {
                    out.put(node("hr"));
                } else if (RichEditorListView.isDetailsHeader(r)) {
                    JSONArray children = new JSONArray().put("▸ ");
                    inline(children, RichTextStyle.toSpannable(((TL_iv.pageBlockDetails) b).title));
                    out.put(node("p").put("children", new JSONArray().put(node("strong").put("children", children))));
                } else if (b instanceof TL_iv.pageBlockPreformatted) {
                    out.put(node("pre").put("children", new JSONArray().put(RichTextStyle.plainOf(b.text))));
                } else {
                    String tag;
                    if (b instanceof TL_iv.pageBlockHeading1 || b instanceof TL_iv.pageBlockHeading2) tag = "h3";
                    else if (RichEditorListView.isHeading(b)) tag = "h4";
                    else if (b instanceof TL_iv.pageBlockPullquote) tag = "aside";
                    else if (b instanceof TL_iv.pageBlockBlockquote || !r.quoteIds.isEmpty()) tag = "blockquote";
                    else tag = "p";
                    JSONArray children = new JSONArray();
                    inline(children, RichTextStyle.toSpannable(b.text, b));
                    if (children.length() == 0) continue;
                    out.put(node(tag).put("children", children));
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        if (out.length() == 0) {
            try {
                out.put(node("p").put("children", new JSONArray().put(" ")));
            } catch (Exception ignore) {
            }
        }
        return out;
    }

    private static JSONObject node(String tag) {
        try {
            return new JSONObject().put("tag", tag).put("children", new JSONArray());
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Spannable → Telegraph inline nodes (b, i, u, s, code, a; line breaks as br). */
    private static void inline(JSONArray out, CharSequence cs) throws Exception {
        if (cs == null || cs.length() == 0) return;
        if (!(cs instanceof Spanned)) { text(out, cs.toString()); return; }
        Spanned sp = (Spanned) cs;
        int n = cs.length();
        for (int pos = 0; pos < n; ) {
            int next = sp.nextSpanTransition(pos, n, CharacterStyle.class);
            if (next <= pos) next = n;
            int flags = 0;
            for (TextStyleSpan span : sp.getSpans(pos, next, TextStyleSpan.class)) flags |= span.getStyleFlags();
            String url = null;
            URLSpanReplacement[] urls = sp.getSpans(pos, next, URLSpanReplacement.class);
            if (urls.length > 0) url = urls[0].getURL();
            String piece;
            MathSpan[] math = sp.getSpans(pos, next, MathSpan.class);
            piece = math.length > 0 && math[0].source != null ? math[0].source : cs.subSequence(pos, next).toString();
            JSONArray target = new JSONArray();
            text(target, piece);
            JSONArray wrapped = target;
            if ((flags & TextStyleSpan.FLAG_STYLE_MONO) != 0 || math.length > 0) wrapped = wrap("code", wrapped);
            if ((flags & TextStyleSpan.FLAG_STYLE_STRIKE) != 0) wrapped = wrap("s", wrapped);
            if ((flags & TextStyleSpan.FLAG_STYLE_UNDERLINE) != 0) wrapped = wrap("u", wrapped);
            if ((flags & TextStyleSpan.FLAG_STYLE_ITALIC) != 0) wrapped = wrap("em", wrapped);
            if ((flags & TextStyleSpan.FLAG_STYLE_BOLD) != 0) wrapped = wrap("strong", wrapped);
            if (url != null) {
                JSONObject a = new JSONObject().put("tag", "a").put("attrs", new JSONObject().put("href", url)).put("children", wrapped);
                wrapped = new JSONArray().put(a);
            }
            for (int k = 0; k < wrapped.length(); k++) out.put(wrapped.get(k));
            pos = next;
        }
    }

    private static JSONArray wrap(String tag, JSONArray children) throws Exception {
        return new JSONArray().put(new JSONObject().put("tag", tag).put("children", children));
    }

    private static void text(JSONArray out, String value) throws Exception {
        String[] lines = value.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.put(new JSONObject().put("tag", "br"));
            if (!lines[i].isEmpty()) out.put(lines[i]);
        }
    }

    private static String publishTelegraph(String title, String author, JSONArray content) throws Exception {
        String token = TPAiStore.getPrivate("telegraph_token");
        for (int attempt = 0; attempt < 2; attempt++) {
            if (TextUtils.isEmpty(token)) {
                JSONObject acc = telegraph("createAccount", "short_name=TelePremium&author_name=" + enc(author));
                token = acc.getJSONObject("result").getString("access_token");
                TPAiStore.putPrivate("telegraph_token", token);
            }
            String safeTitle = title.length() > 256 ? title.substring(0, 255) : title;
            String body = "access_token=" + enc(token) + "&title=" + enc(safeTitle) + "&author_name=" + enc(author)
                    + "&content=" + enc(content.toString()) + "&return_content=false";
            try {
                JSONObject page = telegraph("createPage", body);
                return page.getJSONObject("result").getString("url");
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (attempt == 0 && msg.contains("ACCESS_TOKEN")) { token = null; continue; }
                throw e;
            }
        }
        throw new IllegalStateException("Telegraph: не удалось опубликовать");
    }

    private static JSONObject telegraph(String method, String form) throws Exception {
        HttpURLConnection http = (HttpURLConnection) new URL("https://api.telegra.ph/" + method).openConnection();
        http.setConnectTimeout(20000);
        http.setReadTimeout(30000);
        http.setRequestMethod("POST");
        http.setDoOutput(true);
        http.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
        byte[] bytes = form.getBytes(StandardCharsets.UTF_8);
        http.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = http.getOutputStream()) { out.write(bytes); }
        int code = http.getResponseCode();
        java.io.InputStream in = code >= 400 ? http.getErrorStream() : http.getInputStream();
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        if (in != null) {
            try (java.io.InputStream s = in) {
                byte[] b = new byte[8192];
                int n;
                while ((n = s.read(b)) > 0) buffer.write(b, 0, n);
            }
        }
        http.disconnect();
        JSONObject json = new JSONObject(buffer.toString(StandardCharsets.UTF_8.name()));
        if (!json.optBoolean("ok")) throw new IllegalStateException("Telegraph: " + json.optString("error", "HTTP " + code));
        return json;
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Exception e) { return ""; }
    }

    // ================================================================== pictures

    interface PathsCallback { void run(ArrayList<String> paths); }

    /**
     * Renders the article with Telegram's own rich message layout and slices it into pages,
     * cutting on empty lines so text is never split across pictures.
     */
    static void renderImages(Context context, int account, Theme.ResourcesProvider rp, TL_iv.RichMessage rich, ViewGroup host, PathsCallback done) {
        if (host == null) { done.run(null); return; }
        int width = Math.max(dp(320), Math.min(host.getWidth() > 0 ? host.getWidth() : AndroidUtilities.displaySize.x, dp(480)));
        RichMessageLayout.PreviewView preview = new RichMessageLayout.PreviewView(context, account, rp);
        preview.setPadding(dp(20), dp(20), dp(20), dp(24));
        preview.set(rich);
        preview.setTranslationX(-width * 4f); // attached (so images load) but off-screen
        host.addView(preview, width, ViewGroup.LayoutParams.WRAP_CONTENT);
        AndroidUtilities.runOnUIThread(() -> {
            ArrayList<String> paths = new ArrayList<>();
            try {
                preview.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                int height = preview.getMeasuredHeight();
                preview.layout(0, 0, width, height);
                int bg = Theme.getColor(Theme.key_windowBackgroundWhite, rp);
                int pageMax = (int) (width * 1.8f);
                File dir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE);
                long stamp = System.currentTimeMillis();
                int y = 0, index = 0;
                while (y < height && index < 30) {
                    int slice = Math.min(pageMax, height - y);
                    Bitmap bitmap = Bitmap.createBitmap(width, slice, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bitmap);
                    canvas.drawColor(bg);
                    canvas.translate(0, -y);
                    preview.draw(canvas);
                    int cut = y + slice < height ? findCut(bitmap, bg, slice) : slice;
                    Bitmap page = cut == slice ? bitmap : Bitmap.createBitmap(bitmap, 0, 0, width, cut);
                    File file = new File(dir, "tp_article_" + stamp + "_" + index + ".jpg");
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        page.compress(Bitmap.CompressFormat.JPEG, 95, out);
                    }
                    if (page != bitmap) page.recycle();
                    bitmap.recycle();
                    paths.add(file.getAbsolutePath());
                    y += cut;
                    index++;
                }
            } catch (Throwable t) {
                FileLog.e(t);
                paths.clear();
            }
            host.removeView(preview);
            done.run(paths);
        }, 900);
    }

    /** Lowest fully empty pixel row in the lower 35% of the slice (or the slice end). */
    private static int findCut(Bitmap bitmap, int bg, int slice) {
        int w = bitmap.getWidth();
        int[] line = new int[w];
        int min = (int) (slice * 0.65f);
        for (int yy = slice - 1; yy > min; yy--) {
            bitmap.getPixels(line, 0, w, 0, yy, w, 1);
            boolean empty = true;
            for (int x = 0; x < w; x += 2) {
                int p = line[x];
                if (Math.abs(Color.red(p) - Color.red(bg)) > 8 || Math.abs(Color.green(p) - Color.green(bg)) > 8 || Math.abs(Color.blue(p) - Color.blue(bg)) > 8) {
                    empty = false;
                    break;
                }
            }
            if (empty) return yy + 1;
        }
        return slice;
    }

    // ================================================================== AI assistant

    private static final String SYSTEM = "You are the writing assistant of a Telegram article editor. "
            + "Answer ONLY with an HTML fragment — no <html>/<body>, no markdown, no code fences, no explanations. "
            + "Allowed tags: h1, h2, h3, p, b, i, u, s, code, pre, blockquote, ul, ol, li, table, tr, th, td, hr, a href. "
            + "Write in the language of the user's text unless asked otherwise.";

    private static final String[] MODES_RU = {"Написать", "Улучшить", "Исправить ошибки", "Сократить", "Расширить", "Структурировать", "Перевести"};
    private static final String[] MODES_EN = {"Write", "Improve", "Fix mistakes", "Shorten", "Expand", "Structure", "Translate"};

    private static String instruction(int mode, String prompt) {
        switch (mode) {
            case 1: return "Improve style and readability. Keep the meaning and the structure.";
            case 2: return "Fix spelling, punctuation and grammar. Change nothing else.";
            case 3: return "Shorten the text roughly by half and keep the key points.";
            case 4: return "Expand the text with details and examples.";
            case 5: return "Restructure the text: add headings, lists and tables where useful.";
            case 6: return "Translate the text into " + (TextUtils.isEmpty(prompt) ? "English" : prompt) + ".";
            default: return "Write an article: " + prompt;
        }
    }

    /**
     * ✨ button: own AI when a TelePremium connection exists (or Telegram's AI is unavailable),
     * otherwise Telegram's native composer.
     */
    static void openAi(Context context, int account, Theme.ResourcesProvider rp, RichEditorListView list, Runnable nativeAi, Runnable openSettings) {
        if (!TPAiStore.hasAny() && UserConfig.getInstance(account).isPremium() && nativeAi != null) {
            nativeAi.run();
            return;
        }
        if (!TPAiStore.hasAny()) {
            new AlertDialog.Builder(context, rp)
                    .setTitle(L("Подключите свой ИИ", "Connect your AI"))
                    .setMessage(L("ИИ Telegram доступен только с Premium. Добавьте свою модель — по API-ключу или через аккаунт OpenRouter — и кнопка ✨ заработает.",
                            "Telegram AI requires Premium. Add your own model — with an API key or an OpenRouter account — and ✨ will work."))
                    .setPositiveButton(L("Настроить", "Set up"), (d, w) -> { if (openSettings != null) openSettings.run(); })
                    .setNegativeButton(L("Отмена", "Cancel"), null)
                    .show();
            return;
        }
        showAiSheet(context, rp, list, openSettings);
    }

    private static void showAiSheet(Context context, Theme.ResourcesProvider rp, RichEditorListView list, Runnable openSettings) {
        int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp);
        int grayText = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp);
        int accent = Theme.getColor(Theme.key_featuredStickers_addButton, rp);
        int field = Theme.getColor(Theme.key_windowBackgroundGray, rp);

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(4), dp(16), dp(16));

        TextView model = new TextView(context);
        model.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        model.setTextColor(accent);
        model.setPadding(0, 0, 0, dp(8));
        box.addView(model);

        HorizontalScrollView chipsScroll = new HorizontalScrollView(context);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(context);
        chipsScroll.addView(chips);
        box.addView(chipsScroll, new LinearLayout.LayoutParams(-1, dp(36)));

        EditText prompt = new EditText(context);
        prompt.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        prompt.setTextColor(textColor);
        prompt.setHintTextColor(grayText);
        prompt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        prompt.setMinLines(4);
        prompt.setMaxLines(8);
        prompt.setGravity(Gravity.TOP);
        prompt.setBackground(roundRect(field, 14));
        prompt.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.topMargin = dp(10);
        box.addView(prompt, plp);

        ScrollView resultScroll = new ScrollView(context);
        TextView result = new TextView(context);
        result.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        result.setTextColor(textColor);
        result.setPadding(dp(12), dp(10), dp(12), dp(10));
        result.setBackground(roundRect(field, 14));
        result.setTextIsSelectable(true);
        resultScroll.addView(result);
        resultScroll.setVisibility(View.GONE);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, Math.max(dp(300), (int) (AndroidUtilities.displaySize.y * 0.42f)));
        rlp.topMargin = dp(10);
        box.addView(resultScroll, rlp);

        LinearLayout actions = new LinearLayout(context);
        actions.setGravity(Gravity.END);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = dp(12);
        box.addView(actions, alp);

        // Full-width sheet: room for the prompt and a long answer.
        org.telegram.ui.ActionBar.BottomSheet dialog = new org.telegram.ui.ActionBar.BottomSheet(context, true, rp);
        dialog.setTitle(L("ИИ-помощник", "AI assistant"), true);
        dialog.setCustomView(box);
        final int[] mode = {list.hasAnyText() ? 1 : 0};
        final String[] html = {null};
        final boolean[] busy = {false};
        final TPAiStore.Connection[] conn = {TPAiStore.getDefault()};

        Runnable updateModel = () -> model.setText("✨ " + conn[0].title() + " · " + (TextUtils.isEmpty(conn[0].model) ? "—"
                : conn[0].model + " · " + TPAiClient.tierLabel(TPAiClient.tier(conn[0], conn[0].model), TPStrings.isRussian())) + "  ▾");
        updateModel.run();
        model.setOnClickListener(v -> {
            List<TPAiStore.Connection> all = TPAiStore.list();
            CharSequence[] names = new CharSequence[all.size() + 1];
            for (int i = 0; i < all.size(); i++) names[i] = all.get(i).title() + " · " + all.get(i).model;
            names[all.size()] = L("Настройки подключений…", "Connection settings…");
            new AlertDialog.Builder(context, rp).setItems(names, (d, which) -> {
                if (which == all.size()) { dialog.dismiss(); if (openSettings != null) openSettings.run(); return; }
                conn[0] = all.get(which);
                TPAiStore.setDefault(conn[0].id);
                updateModel.run();
            }).show();
        });

        Runnable[] renderChips = new Runnable[1];
        renderChips[0] = () -> {
            chips.removeAllViews();
            String[] labels = TPStrings.isRussian() ? MODES_RU : MODES_EN;
            for (int i = 0; i < labels.length; i++) {
                final int index = i;
                boolean on = i == mode[0];
                TextView chip = new TextView(context);
                chip.setText(labels[i]);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                chip.setTextColor(on ? Color.WHITE : textColor);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(dp(14), 0, dp(14), 0);
                chip.setBackground(roundRect(on ? accent : field, 16));
                chip.setOnClickListener(v -> { mode[0] = index; renderChips[0].run(); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(34));
                lp.rightMargin = dp(8);
                chips.addView(chip, lp);
            }
            prompt.setHint(mode[0] == 0 ? L("О чём написать? Например: обзор смартфона с таблицей характеристик", "What to write? E.g. a phone review with a spec table")
                    : mode[0] == 6 ? L("На какой язык? (по умолчанию английский)", "Target language (English by default)")
                    : L("Дополнительное пожелание (необязательно)", "Extra wish (optional)"));
        };
        renderChips[0].run();

        TextView generate = button(context, L("Сгенерировать", "Generate"), accent, true);
        TextView insert = button(context, L("Вставить", "Insert"), accent, false);
        TextView replace = button(context, L("Заменить всё", "Replace all"), accent, true);
        insert.setVisibility(View.GONE);
        replace.setVisibility(View.GONE);
        actions.addView(insert);
        actions.addView(replace);
        actions.addView(generate);

        generate.setOnClickListener(v -> {
            if (busy[0]) return;
            String p = prompt.getText().toString().trim();
            String doc = list.tpDocumentHtml();
            if (mode[0] == 0 && p.isEmpty()) { AndroidUtilities.shakeViewSpring(prompt, 4); return; }
            if (mode[0] != 0 && TextUtils.isEmpty(RichMessageConvert.rowsToCharSequence(list.rows).toString().trim())) {
                Toast.makeText(context, L("Сначала напишите текст", "Write some text first"), Toast.LENGTH_SHORT).show();
                return;
            }
            StringBuilder user = new StringBuilder(instruction(mode[0], p));
            if (mode[0] != 0 && mode[0] != 6 && !p.isEmpty()) user.append("\nAdditional wish: ").append(p);
            if (!TextUtils.isEmpty(doc)) user.append(mode[0] == 0 ? "\n\nCurrent article (context):\n" : "\n\nArticle:\n").append(doc);
            busy[0] = true;
            generate.setText(L("Думаю…", "Thinking…"));
            generate.setAlpha(0.6f);
            TPAiStore.Connection c = conn[0];
            TPAiClient.async(() -> TPAiClient.complete(c, SYSTEM, user.toString()), (answer, error) -> {
                busy[0] = false;
                generate.setText(L("Ещё раз", "Again"));
                generate.setAlpha(1f);
                if (error != null) {
                    resultScroll.setVisibility(View.VISIBLE);
                    result.setText(L("Ошибка: ", "Error: ") + error);
                    insert.setVisibility(View.GONE);
                    replace.setVisibility(View.GONE);
                    return;
                }
                html[0] = cleanHtml(answer);
                resultScroll.setVisibility(View.VISIBLE);
                result.setText(Build.VERSION.SDK_INT >= 24 ? Html.fromHtml(html[0], Html.FROM_HTML_MODE_COMPACT) : Html.fromHtml(html[0]));
                insert.setVisibility(View.VISIBLE);
                replace.setVisibility(mode[0] == 0 && !list.hasAnyText() ? View.GONE : View.VISIBLE);
            });
        });
        insert.setOnClickListener(v -> {
            if (html[0] == null) return;
            if (!list.tpInsertHtml(html[0], false)) list.tpInsertHtml("<p>" + TextUtils.htmlEncode(result.getText().toString()) + "</p>", false);
            dialog.dismiss();
        });
        replace.setOnClickListener(v -> {
            if (html[0] == null) return;
            if (list.tpInsertHtml(html[0], true)) dialog.dismiss();
        });
        dialog.show();
    }

    private static String cleanHtml(String answer) {
        String s = answer == null ? "" : answer.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            s = nl > 0 ? s.substring(nl + 1) : s.substring(3);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        }
        s = s.replaceAll("(?is)</?(html|body|head)[^>]*>", "").trim();
        if (!s.contains("<")) {
            StringBuilder sb = new StringBuilder();
            for (String para : s.split("\n\\s*\n")) sb.append("<p>").append(TextUtils.htmlEncode(para.trim()).replace("\n", "<br>")).append("</p>");
            s = sb.toString();
        }
        return s;
    }

    private static TextView button(Context context, String label, int accent, boolean filled) {
        TextView v = new TextView(context);
        v.setText(label);
        v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        v.setTypeface(AndroidUtilities.bold());
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(16), 0, dp(16), 0);
        v.setTextColor(filled ? Color.WHITE : accent);
        v.setBackground(filled ? roundRect(accent, 18) : null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(38));
        lp.leftMargin = dp(6);
        v.setLayoutParams(lp);
        return v;
    }

    private static GradientDrawable roundRect(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }
}
