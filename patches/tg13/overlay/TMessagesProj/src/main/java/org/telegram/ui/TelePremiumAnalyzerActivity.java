package org.telegram.ui;

import android.app.AlertDialog;
import android.content.*;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.List;
import org.json.*;
import org.telegram.messenger.*;
import org.telegram.messenger.telepremium.ai.*;
import org.telegram.messenger.telepremium.aicenter.*;
import org.telegram.messenger.telepremium.cloud.vault.*;
import org.telegram.ui.ActionBar.*;

/** One center, shared account-qualified index and theme bridge for both Telegram shells. */
public final class TelePremiumAnalyzerActivity extends BaseFragment {
  private final CenterIndex index = new CenterIndex();
  private final CenterData.SourceFilter filter = new CenterData.SourceFilter();
  private CenterData.Message pendingImport;
  private boolean pendingTranscription;
  private boolean reportRunning;
  private int reportPage;
  private final Set<String> expandedEvidence = new HashSet<>();
  private static final Set<TelePremiumAnalyzerActivity> LIVE =
      Collections.newSetFromMap(new WeakHashMap<>());

  public static void onApplicationBackground() {
    for (TelePremiumAnalyzerActivity center : new ArrayList<>(LIVE)) center.lockForBackground();
  }

  private void lockForBackground() {
    if (!TPAiStore.getPrivate("center_lock").isEmpty() || lockPresent()) {
      savedScroll = scroll == null ? savedScroll : scroll.getScrollY();
      unlocked = false;
      if (body != null) clearBody();
      for (AlertDialog dialog : new ArrayList<>(centerDialogs)) dialog.dismiss();
    }
  }

  private boolean archiveDetail;
  private CenterStats statistics;
  private int activitySort, activityMode;
  private final Set<String> chartGroups = new LinkedHashSet<>();
  private final Set<Long> comparedAuthors = new LinkedHashSet<>();
  private List<CenterData.Message> drilldown;
  private boolean authorReport;
  private String authorSearch = "";
  private int authorSort, authorPage, authorListPage;
  private long pendingAuthor = Long.MIN_VALUE;
  private CenterReports.Report authorSnapshot;
  private long profileAuthor = Long.MIN_VALUE;
  private String authorKind = "";
  private AlertDialog mediaDialog;
  private final List<AlertDialog> centerDialogs = new ArrayList<>();
  private final Set<String> pickedMedia = new HashSet<>();
  private String mediaSearch = "";
  private String mediaKind = "";
  private String mediaCategory = "";
  private Set<String> semanticHits;
  private int transcriptPage;
  private String detailKey = "";
  private int mediaPage;
  private int uiVersion;
  private final android.os.Handler uiHandler =
      new android.os.Handler(android.os.Looper.getMainLooper());
  private CenterReports.Report report;
  private String query = "";
  private boolean detailed = true;
  private int reportKind;
  private int savedScroll;
  private final java.util.concurrent.atomic.AtomicBoolean aiCancelled =
      new java.util.concurrent.atomic.AtomicBoolean();
  private long observedPrivacyEpoch = CenterIndex.generation();
  private final Runnable privacyWatcher =
      new Runnable() {
        @Override
        public void run() {
          if (!foreground) return;
          if (observedPrivacyEpoch != CenterIndex.generation()) {
            observedPrivacyEpoch = CenterIndex.generation();
            report = null;
            drilldown = null;
            if (mediaDialog != null) {
              mediaDialog.dismiss();
              mediaDialog = null;
            }
            CenterThumbnails.clear();
            if (unlocked) reload();
          }
          uiHandler.postDelayed(this, 1500);
        }
      };
  private CenterSync sync;
  private LinearLayout root, body, navigation;
  private TelePremiumCenterUi ui;
  private LinearLayout dock;
  private int backTo = -1;
  private static final int[] NAV = {1, 6, 2, 4, 5, 7};
  private ProgressBar loadingBar;
  private HorizontalScrollView navigationScroll;
  private final ArrayList<LinearLayout> navItems = new ArrayList<>();
  private static final int[] NAV_ICONS = {
    R.drawable.tp_aic_sources,
    R.drawable.tp_aic_summaries,
    R.drawable.tp_aic_activity,
    R.drawable.tp_aic_authors,
    R.drawable.tp_aic_media,
    R.drawable.tp_aic_transcriber,
    R.drawable.tp_aic_analyst,
    R.drawable.tp_aic_reports,
    R.drawable.tp_aic_settings,
    R.drawable.tp_aic_accounts
  };
  private TextView scopeLine, status;
  private ScrollView scroll;
  private int screen = 1;
  private boolean unlocked, foreground;
  private String secretKind = "PASSWORD";
  private final VaultAttemptLimiter limiter =
      new VaultAttemptLimiter(
          new VaultAttemptLimiter.Store() {
            public long getLong(String k, long d) {
              return CenterIndex.settings().optLong("lock_" + k, d);
            }

            public void putLong(String k, long v) {
              CenterIndex.setting("lock_" + k, v);
            }
          },
          System::currentTimeMillis);
  private static final String[] SCREENS = {
    "sources",
    "summaries",
    "activity",
    "authors",
    "media",
    "transcriber",
    "analyst",
    "reports",
    "settings"
  };

  public static TelePremiumAnalyzerActivity openScreen(int screen) {
    TelePremiumAnalyzerActivity a = new TelePremiumAnalyzerActivity();
    a.screen = Math.max(0, Math.min(9, screen));
    return a;
  }

  public static TelePremiumAnalyzerActivity forMessage(MessageObject object) {
    TelePremiumAnalyzerActivity activity = new TelePremiumAnalyzerActivity();
    int a = object.currentAccount;
    long did = object.getDialogId();
    CenterData.Source source = new CenterData.Source();
    source.account = a;
    source.owner = UserConfig.getInstance(a).getClientUserId();
    source.dialog = did;
    source.accountName = UserObject.getUserName(UserConfig.getInstance(a).getCurrentUser());
    org.telegram.tgnet.TLRPC.Chat chat =
        did < 0 ? MessagesController.getInstance(a).getChat(-did) : null;
    source.title = chat == null ? CenterText.get("transcriber") : chat.title;
    source.canonical =
        did < 0
            ? (ChatObject.isChannel(chat) ? "c:" : "g:") + (-did)
            : "p:" + Math.min(source.owner, did) + ":" + Math.max(source.owner, did);
    activity.pendingImport = CenterSync.convert(source, object.messageOwner);
    activity.pendingTranscription = true;
    activity.screen = 5;
    return activity;
  }

  public static String t(String key) {
    return CenterText.get(key);
  }

  private int dp(float n) {
    return AndroidUtilities.dp(n);
  }

  private String tr(String en, String ru) {
    return org.telegram.messenger.telepremium.TPStrings.isRussian() ? ru : en;
  }

  private TextView label(String text, int size) {
    TextView v = new TextView(getParentActivity());
    v.setText(text);
    v.setTextSize(size);
    v.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
    v.setPadding(dp(16), dp(10), dp(16), dp(10));
    return v;
  }

  private String navTitle(int tab) {
    switch (tab) {
      case 1:
        return tr("Summary", "Сводка");
      case 6:
        return tr("Analyst", "Аналитик");
      case 2:
        return tr("Activity", "Активность");
      case 4:
        return tr("Media", "Медиа");
      case 5:
        return tr("Transcriber", "Транскрибатор");
      case 7:
        return tr("Reports", "Отчёты");
      case 8:
        return tr("Settings", "Настройки");
      case 0:
        return tr("Sources", "Источники");
      case 9:
        return tr("Accounts", "Аккаунты и фильтры");
      default:
        return tr("Authors", "Авторы");
    }
  }

  private String describe(int tab) {
    switch (tab) {
      case 1:
        return tr(
            "A structured digest of your groups for the chosen period, with proof for every point.",
            "Структурированная сводка по вашим группам за период. К каждому пункту — доказательства.");
      case 2:
      case 3:
        return tr(
            "Which groups and people were active, and when.",
            "Какие группы и люди были активны и когда.");
      case 4:
        return tr(
            "Photos, videos and documents from your groups. Search them, read the text on them, ask AI to describe them.",
            "Фото, видео и документы из ваших групп. Ищите их, распознавайте текст, просите ИИ описать.");
      case 5:
        return tr(
            "Turns voice messages, audio and video into text with timecodes.",
            "Превращает голосовые, аудио и видео в текст с таймкодами.");
      case 7:
        return tr("Everything you have saved.", "Всё, что вы сохранили.");
      case 8:
        return tr(
            "Sources, models, limits, background work and protection.",
            "Источники, модели, лимиты, фоновая работа и защита.");
      case 0:
        return tr(
            "Choose which groups of each account are analysed.",
            "Выберите, какие группы каждого аккаунта анализируются.");
      default:
        return "";
    }
  }

  private void updateNavigation() {
    if (navItems.isEmpty()) return;
    int current = screen == 3 ? 2 : screen;
    int activeIndex = -1;
    for (int i = 0; i < NAV.length; i++) if (NAV[i] == current) activeIndex = i;
    for (int i = 0; i < navItems.size(); i++) {
      LinearLayout item = navItems.get(i);
      boolean on = i == activeIndex;
      int color = on ? ui.accent() : ui.sub();
      ((ImageView) item.getChildAt(0)).setImageDrawable(ui.drawable(NAV_ICONS[NAV[i]], color));
      TextView title = (TextView) item.getChildAt(1);
      title.setTextColor(color);
      title.setTypeface(on ? AndroidUtilities.bold() : Typeface.DEFAULT);
      item.setBackground(on ? ui.shape(TelePremiumCenterUi.alpha(ui.accent(), 28), 14, 0, 0) : null);
    }
    if (activeIndex >= 0) {
      final LinearLayout item = navItems.get(activeIndex);
      navigationScroll.post(
          () -> navigationScroll.smoothScrollTo(Math.max(0, item.getLeft() - dp(120)), 0));
    }
  }

  private boolean lockPresent() {
    return ApplicationLoader.applicationContext
        .getSharedPreferences("tp_center_gate", Context.MODE_PRIVATE)
        .getBoolean("protected", false);
  }

  private void lockFlag(boolean value) {
    ApplicationLoader.applicationContext
        .getSharedPreferences("tp_center_gate", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("protected", value)
        .commit();
  }

  private void avatar(LinearLayout container, int account, long peer) {
    org.telegram.ui.Components.BackupImageView image =
        new org.telegram.ui.Components.BackupImageView(getParentActivity());
    image.setRoundRadius(dp(22));
    image.getImageReceiver().setCurrentAccount(account);
    org.telegram.ui.Components.AvatarDrawable placeholder =
        new org.telegram.ui.Components.AvatarDrawable();
    org.telegram.tgnet.TLRPC.User user =
        peer > 0 ? MessagesController.getInstance(account).getUser(peer) : null;
    org.telegram.tgnet.TLRPC.Chat chat =
        peer < 0 ? MessagesController.getInstance(account).getChat(-peer) : null;
    if (user != null) {
      placeholder.setInfo(user);
      image.setForUserOrChat(user, placeholder);
    } else if (chat != null) {
      placeholder.setInfo(chat);
      image.setForUserOrChat(chat, placeholder);
    } else {
      placeholder.setInfo(peer, t("sources"), null);
      image.setImageDrawable(placeholder);
    }
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
    lp.setMargins(dp(12), dp(8), dp(8), dp(8));
    container.addView(image, lp);
  }

  private AlertDialog.Builder dialogBuilder() {
    int background = getThemedColor(Theme.key_windowBackgroundWhite);
    boolean dark =
        Color.red(background) * 299 + Color.green(background) * 587 + Color.blue(background) * 114
            < 128000;
    Context themed =
        new android.view.ContextThemeWrapper(
            getParentActivity(),
            dark
                ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert);
    return new AlertDialog.Builder(themed) {
      @Override
      public AlertDialog create() {
        AlertDialog dialog = super.create();
        centerDialogs.add(dialog);
        dialog.setOnDismissListener(d -> centerDialogs.remove(dialog));
        if (dialog.getWindow() != null) {
          dialog
              .getWindow()
              .setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(background));
          if (CenterIndex.settings().optBoolean("secure", true))
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        return dialog;
      }
    };
  }

  private void heading(String key) {
    body.addView(ui.section(t(key)));
  }

  private TextView row(String text, Runnable action) {
    TextView v = label(text, 15);
    v.setMinHeight(dp(52));
    v.setGravity(Gravity.CENTER_VERTICAL);
    v.setPadding(dp(16), dp(12), dp(16), dp(12));
    v.setBackground(
        action == null
            ? ui.shape(ui.card(), 14, ui.line(), 1)
            : ui.ripple(ui.shape(ui.card(), 14, ui.line(), 1), 14));
    if (action != null) {
      v.setOnClickListener(w -> action.run());
      android.graphics.drawable.Drawable chevron = ui.drawable(R.drawable.tp_aic_chevron, ui.sub());
      if (chevron != null) {
        chevron.setBounds(0, 0, dp(20), dp(20));
        v.setCompoundDrawables(null, null, chevron, null);
      }
    }
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.setMargins(dp(12), dp(3), dp(12), dp(3));
    body.addView(v, p);
    return v;
  }

  private void button(String key, Runnable action) {
    TextView b = ui.button(t(key), false, 0, w -> action.run());
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.setMargins(dp(12), dp(4), dp(12), dp(4));
    body.addView(b, p);
  }

  private void primary(String key, int icon, Runnable action) {
    TextView b = ui.button(t(key), true, icon, w -> action.run());
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.setMargins(dp(12), dp(4), dp(12), dp(4));
    body.addView(b, p);
  }

  private void info(String text) {
    TextView v = label(text, 14);
    v.setPadding(dp(20), dp(5), dp(20), dp(5));
    body.addView(v);
  }

  private void toast(String text) {
    if (getParentActivity() != null)
      Toast.makeText(getParentActivity(), text, Toast.LENGTH_LONG).show();
  }

  private void error(String e) {
    if (e != null) toast(t("error") + ": " + e);
  }

  private void input(String key, String value, java.util.function.Consumer<String> result) {
    if (key.equals("pattern")) {
      patternInput(result);
      return;
    }
    EditText in = new EditText(getParentActivity());
    in.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
    in.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText));
    in.setBackgroundTintList(
        android.content.res.ColorStateList.valueOf(
            getThemedColor(Theme.key_windowBackgroundWhiteBlueText)));
    if (key.equals("password") || key.equals("pin")) in.setInputType(key.equals("pin") ? 18 : 129);
    in.setText(value);
    in.setMaxLines(5);
    dialogBuilder()
        .setTitle(t(key))
        .setView(in)
        .setPositiveButton(t("save"), (d, w) -> result.accept(in.getText().toString().trim()))
        .setNegativeButton(t("cancel"), null)
        .show();
  }

  private void patternInput(java.util.function.Consumer<String> done) {
    class Grid extends View {
      final StringBuilder pattern = new StringBuilder();
      final Paint paint = new Paint(3);
      float lastX, lastY;

      Grid(Context c) {
        super(c);
        setMinimumHeight(dp(280));
      }

      @Override
      protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        paint.setStrokeWidth(dp(3));
        float x = 0, y = 0;
        for (int i = 0; i < pattern.length(); i++) {
          int n = pattern.charAt(i) - '0';
          float nx = getWidth() * (n % 3 + 1) / 4f, ny = getHeight() * (n / 3 + 1) / 4f;
          if (i > 0) canvas.drawLine(x, y, nx, ny, paint);
          x = nx;
          y = ny;
        }
        for (int n = 0; n < 9; n++)
          canvas.drawCircle(
              getWidth() * (n % 3 + 1) / 4f, getHeight() * (n / 3 + 1) / 4f, dp(9), paint);
      }

      @Override
      public boolean onTouchEvent(android.view.MotionEvent e) {
        if (e.getAction() == 0) pattern.setLength(0);
        if (e.getAction() == 0 || e.getAction() == 2) {
          for (int n = 0; n < 9; n++)
            if (Math.hypot(
                        e.getX() - getWidth() * (n % 3 + 1) / 4f,
                        e.getY() - getHeight() * (n / 3 + 1) / 4f)
                    < dp(28)
                && pattern.indexOf("" + n) < 0) pattern.append(n);
          invalidate();
        }
        return true;
      }
    }
    Grid grid = new Grid(getParentActivity());
    dialogBuilder()
        .setTitle(t("pattern"))
        .setView(grid)
        .setPositiveButton(t("confirm"), (d, w) -> done.accept(grid.pattern.toString()))
        .setNegativeButton(t("cancel"), null)
        .show();
  }

  private void choices(String title, String[] values, java.util.function.IntConsumer result) {
    dialogBuilder().setTitle(t(title)).setItems(values, (d, w) -> result.accept(w)).show();
  }

  private void toggle(String key, boolean checked, java.util.function.Consumer<Boolean> change) {
    final Switch sw = ui.switchView(checked, change);
    LinearLayout r = ui.row(0, t(key), null, sw, w -> sw.toggle());
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.setMargins(dp(12), dp(3), dp(12), dp(3));
    body.addView(r, p);
  }

  @Override
  public View createView(Context context) {
    LIVE.add(this);
    actionBar.setBackButtonImage(R.drawable.ic_ab_back);
    actionBar.setTitle(t("title"));
    actionBar.setActionBarMenuOnItemClick(
        new ActionBar.ActionBarMenuOnItemClick() {
          @Override
          public void onItemClick(int id) {
            if (id == -1) {
              if (unlocked && screen == 9) show(0);
              else if (unlocked && screen == 0) show(backTo >= 0 ? backTo : 8);
              else finishFragment();
            } else if (id == 1 && unlocked) show(8);
          }
        });
    actionBar.createMenu().addItem(1, R.drawable.tp_aic_settings);
    ui = new TelePremiumCenterUi(context, this::getThemedColor);
    root = new LinearLayout(context);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(ui.page());
    scopeLine = new TextView(context);
    LinearLayout statusStrip = new LinearLayout(context);
    statusStrip.setOrientation(LinearLayout.VERTICAL);
    status =
        new TextView(context) {
          @Override
          public void setText(CharSequence text, BufferType type) {
            super.setText(text, type);
            boolean empty = text == null || text.length() == 0;
            if (loadingBar != null) loadingBar.setVisibility(empty ? View.GONE : View.VISIBLE);
            setVisibility(empty ? View.GONE : View.VISIBLE);
          }
        };
    status.setTextSize(12.5f);
    status.setTextColor(ui.accent());
    status.setPadding(dp(18), dp(6), dp(18), dp(6));
    status.setVisibility(View.GONE);
    statusStrip.addView(status);
    loadingBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
    loadingBar.setIndeterminate(true);
    loadingBar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ui.accent()));
    loadingBar.setVisibility(View.GONE);
    statusStrip.addView(loadingBar, new LinearLayout.LayoutParams(-1, dp(3)));
    root.addView(statusStrip, new LinearLayout.LayoutParams(-1, -2));
    scroll = new ScrollView(context);
    scroll.setBackgroundColor(ui.page());
    scroll.setClipToPadding(false);
    body = new LinearLayout(context);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(0, 0, 0, dp(28));
    scroll.addView(body);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    dock = new LinearLayout(context);
    dock.setOrientation(LinearLayout.VERTICAL);
    dock.setBackgroundColor(ui.card());
    dock.setVisibility(View.GONE);
    root.addView(dock, new LinearLayout.LayoutParams(-1, -2));
    View navLine = new View(context);
    navLine.setBackgroundColor(ui.line());
    root.addView(navLine, new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f))));
    navigationScroll = new HorizontalScrollView(context);
    navigationScroll.setHorizontalScrollBarEnabled(false);
    navigationScroll.setBackgroundColor(ui.card());
    navigation = new LinearLayout(context);
    navigation.setPadding(dp(6), dp(6), dp(6), dp(6));
    navigationScroll.addView(navigation);
    root.addView(navigationScroll, new LinearLayout.LayoutParams(-1, dp(66)));
    navItems.clear();
    for (int i = 0; i < NAV.length; i++) {
      final int tab = NAV[i];
      LinearLayout item = new LinearLayout(context);
      item.setOrientation(LinearLayout.VERTICAL);
      item.setGravity(Gravity.CENTER);
      item.setPadding(dp(2), dp(4), dp(2), dp(4));
      item.addView(ui.icon(NAV_ICONS[tab], ui.sub(), 24));
      TextView title = ui.text(navTitle(tab), 11, ui.sub(), false);
      title.setSingleLine(true);
      title.setGravity(Gravity.CENTER);
      title.setPadding(0, dp(2), 0, 0);
      item.addView(title);
      item.setOnClickListener(w -> openTab(tab));
      navigation.addView(item, new LinearLayout.LayoutParams(dp(88), -1));
      navItems.add(item);
    }
    fragmentView = root;
    return root;
  }

  @Override
  public void onResume() {
    super.onResume();
    foreground = true;
    uiHandler.removeCallbacks(privacyWatcher);
    uiHandler.post(privacyWatcher);
    applySecure();
    if (!unlocked) unlock();
    else reload();
  }

  @Override
  public void onPause() {
    super.onPause();
    savedScroll = scroll == null ? 0 : scroll.getScrollY();
    foreground = false;
    for (AlertDialog dialog : new ArrayList<>(centerDialogs)) dialog.dismiss();
    if (mediaDialog != null) {
      mediaDialog.dismiss();
      mediaDialog = null;
    }
    if (ApplicationLoader.mainInterfacePaused
        && (!TPAiStore.getPrivate("center_lock").isEmpty() || lockPresent())) {
      unlocked = false;
      if (body != null) clearBody();
    }
    if (getParentActivity() != null)
      getParentActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
  }

  @Override
  public void onFragmentDestroy() {
    LIVE.remove(this);
    uiHandler.removeCallbacksAndMessages(null);
    if (sync != null) sync.cancel();
    aiCancelled.set(true);
    unlocked = false;
    super.onFragmentDestroy();
  }

  private void applySecure() {
    if (getParentActivity() != null) {
      if (CenterIndex.settings().optBoolean("secure", true))
        getParentActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
      else getParentActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
  }

  private void clearBody() {
    body.removeAllViews();
    if (dock != null) {
      dock.removeAllViews();
      dock.setVisibility(View.GONE);
    }
  }

  private void unlock() {
    String record = TPAiStore.getPrivate("center_lock");
    if (record.isEmpty() && lockPresent()) {
      clearBody();
      heading("locked");
      info(t("lockLost"));
      button(
          "clear",
          () ->
              dialogBuilder()
                  .setMessage(t("confirmClear"))
                  .setPositiveButton(
                      t("confirm"),
                      (d, w) -> {
                        clearCenterData();
                        lockFlag(false);
                        unlocked = true;
                        reload();
                      })
                  .setNegativeButton(t("cancel"), null)
                  .show());
      return;
    }
    if (record.isEmpty()) {
      unlocked = true;
      reload();
      return;
    }
    clearBody();
    heading("locked");
    button(
        "unlock",
        () ->
            input(
                TPAiStore.getPrivate("center_lock_kind").equals("PATTERN") ? "pattern" : "password",
                "",
                raw -> {
                  if (limiter.remainingMs("aicenter") > 0) {
                    toast(t("wrong"));
                    return;
                  }
                  CenterStore.IO.execute(
                      () -> {
                        String err = null;
                        try {
                          byte[] master =
                              android.util.Base64.decode(
                                  TPAiStore.getPrivate("center_lock_master"), 2);
                          char[] secret =
                              VaultSecretPolicy.toSecret(
                                  VaultSecretPolicy.Kind.valueOf(
                                      TPAiStore.getPrivate("center_lock_kind")),
                                  raw);
                          byte[] fk =
                              VaultFolderLock.open(
                                  VaultFolderLock.Record.parse(record), secret, "aicenter", master);
                          Arrays.fill(fk, (byte) 0);
                          Arrays.fill(master, (byte) 0);
                          Arrays.fill(secret, '\0');
                        } catch (Exception e) {
                          err = t("wrong");
                        }
                        if (err == null) limiter.recordSuccess("aicenter");
                        else limiter.recordFailure("aicenter");
                        String fail = err;
                        AndroidUtilities.runOnUIThread(
                            () -> {
                              if (!foreground) return;
                              if (fail == null) {
                                unlocked = true;
                                reload();
                              } else toast(fail);
                            });
                      });
                }));
    if (VaultFolderBiometric.isEnrolled(getParentActivity(), "aicenter"))
      button(
          "biometric",
          () ->
              VaultFolderBiometric.unlock(
                  getParentActivity(),
                  "aicenter",
                  t("unlock"),
                  t("cancel"),
                  new VaultFolderBiometric.KeyCallback() {
                    public void onKey(byte[] key) {
                      try {
                        if (VaultFolderLock.Record.parse(record)
                            .keyId
                            .equals(VaultFolderLock.keyId(key))) {
                          unlocked = true;
                          reload();
                        }
                      } catch (Exception e) {
                        error(e.getMessage());
                      } finally {
                        Arrays.fill(key, (byte) 0);
                      }
                    }

                    public void onError(String e) {
                      error(e);
                    }
                  }));
  }

  private void reload() {
    status.setText(t("loading"));
    index.load(
        e -> {
          if (!unlocked || !foreground) return;
          status.setText(e == null ? "" : t("error") + ": " + e);
          if (pendingImport != null) {
            CenterData.Message message = pendingImport;
            pendingImport = null;
            importMedia(message);
            return;
          }
          show(screen);
          scroll.post(() -> scroll.scrollTo(0, savedScroll));
        });
  }

  private String scopeSummary() {
    Set<Integer> accounts = new HashSet<>(index.scope.whole);
    accounts.removeAll(index.scope.excluded);
    List<String> titles = new ArrayList<>();
    for (CenterData.Source source : index.sources)
      if (index.scope.requests(source)) {
        accounts.add(source.account);
        if (titles.size() < 3) titles.add(source.title);
      }
    java.text.DateFormat date = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT);
    return t("account")
        + ": "
        + accounts.size()
        + " · "
        + t("sources")
        + ": "
        + index.sources.stream().filter(index.scope::requests).count()
        + " · "
        + t("messages")
        + ": "
        + (drilldown == null
            ? index.selected().size()
            : CenterData.select(drilldown, index.sources, index.scope).size())
        + "\n"
        + date.format(new Date(index.scope.from * 1000))
        + " — "
        + date.format(new Date(index.scope.to * 1000))
        + " · "
        + t("authors")
        + ": "
        + (index.scope.authors.isEmpty() ? t("all") : index.scope.authors.size())
        + " · "
        + t("type")
        + ": "
        + (index.scope.kinds.isEmpty() ? t("all") : index.scope.kinds.size())
        + "\n"
        + String.join(" · ", titles);
  }

  private void show(int tab) {
    if (!unlocked) return;
    int oldScreen = screen;
    if (tab == 0 && oldScreen != 0 && oldScreen != 9) backTo = oldScreen;
    screen = tab;
    if (tab == 7 && oldScreen != 7) archiveDetail = false;
    uiVersion++;
    clearBody();
    dock.removeAllViews();
    dock.setVisibility(View.GONE);
    actionBar.setTitle(navTitle(tab == 3 ? 2 : tab));
    updateNavigation();
    scroll.scrollTo(0, 0);
    String caption = describe(tab);
    boolean flow = tab == 1 || tab == 2 || tab == 3 || tab == 4 || tab == 5 || tab == 6;
    if (tab == 2 || tab == 3) {
      LinearLayout seg =
          ui.segmented(
              new String[] {tr("Groups", "Группы"), tr("Authors", "Авторы")},
              tab == 2 ? 0 : 1,
              i -> show(i == 0 ? 2 : 3));
      body.addView(seg, margins(-1, -2, 12, 10, 12, 0));
    }
    if (!caption.isEmpty() && tab != 6) {
      TextView hint = ui.text(caption, 13, ui.sub(), false);
      hint.setPadding(dp(18), dp(10), dp(18), dp(2));
      body.addView(hint);
    }
    if (flow) body.addView(scopeBar());
    switch (tab) {
      case 0:
        sources();
        break;
      case 1:
        summary();
        break;
      case 2:
        statistics(false);
        break;
      case 3:
        statistics(true);
        break;
      case 4:
        mediaLibrary();
        break;
      case 5:
        transcriber();
        break;
      case 6:
        analyst();
        break;
      case 7:
        reportArchive();
        break;
      case 8:
        settings();
        break;
      case 9:
        accounts();
        break;
      default:
        info(t("empty"));
    }
  }

  private String periodLabel() {
    if (index.scope.sinceRead) return t("sinceRead");
    long span = Math.round((index.scope.to - index.scope.from) / 86400.0);
    if (span == 1) return tr("1 day", "1 день");
    if (span == 7) return tr("7 days", "7 дней");
    if (span == 30) return tr("30 days", "30 дней");
    if (span == 90) return tr("90 days", "90 дней");
    return dateOnly(index.scope.from) + " — " + dateOnly(index.scope.to);
  }

  private void choosePeriod() {
    choices(
        "period",
        new String[] {
          tr("1 day", "1 день"),
          tr("7 days", "7 дней"),
          tr("30 days", "30 дней"),
          tr("90 days", "90 дней"),
          t("customDates"),
          t("sinceRead")
        },
        this::pickPeriod);
  }

  /** One light row under every analysis screen: what is analysed and for which period. */
  private View scopeBar() {
    android.widget.HorizontalScrollView row = ui.chipRow();
    long sourceCount = index.sources.stream().filter(index.scope::requests).count();
    row.getChildAt(0).setPadding(dp(12), dp(8), dp(12), dp(2));
    ui.addChip(
        row,
        ui.chip(
            sourceCount == 0
                ? tr("Choose sources", "Выбрать источники")
                : tr("Sources", "Источники") + ": " + sourceCount,
            sourceCount == 0,
            v -> show(0)));
    ui.addChip(row, ui.chip(periodLabel(), false, v -> choosePeriod()));
    if (drilldown != null)
      ui.addChip(
          row,
          ui.chip(
              tr("Subset of a report", "Выборка из отчёта") + "  ✕",
              true,
              v -> {
                drilldown = null;
                show(screen);
              }));
    return row;
  }

  // ------------------------------------------------------------------ sources and accounts

  private int sourceLimit = 100;

  private LinearLayout.LayoutParams margins(int w, int h, int l, int t, int r, int b) {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
    p.setMargins(dp(l), dp(t), dp(r), dp(b));
    return p;
  }

  private Map<String, Integer> sourceCounts() {
    return CenterData.SourceFilter.counts(
        index.sources, index.messages, index.scope.from, index.scope.to);
  }

  private List<Integer> activeAccounts() {
    List<Integer> list = new ArrayList<>();
    for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++)
      if (UserConfig.getInstance(a).isClientActivated()) list.add(a);
    return list;
  }

  private String accountTitle(int account) {
    return UserObject.getUserName(UserConfig.getInstance(account).getCurrentUser());
  }

  private int accountMode(int account) {
    return index.scope.excluded.contains(account) ? 2 : index.scope.whole.contains(account) ? 0 : 1;
  }

  private String modeTitle(int mode) {
    return mode == 0 ? t("all") : mode == 1 ? t("selected") : t("excluded");
  }

  private int modeColor(int mode) {
    return mode == 0 ? ui.good() : mode == 1 ? ui.accent() : ui.bad();
  }

  private void applyAccountMode(int account, int mode) {
    Runnable apply =
        () -> {
          index.scope.excluded.remove(account);
          index.scope.whole.remove(account);
          if (mode == 0) index.scope.whole.add(account);
          if (mode == 2) {
            aiCancelled.set(true);
            report = null;
            index.scope.excluded.add(account);
            index.messages.removeIf(m -> m.account == account);
            CenterIndex.logout(account);
          }
          index.saveScope();
          show(screen);
        };
    if (mode == 2 && accountMode(account) != 2)
      dialogBuilder()
          .setMessage(
              tr(
                  "Everything the Center stored for this account will be removed from the index.",
                  "Всё, что центр сохранил по этому аккаунту, будет удалено из индекса."))
          .setPositiveButton(t("confirm"), (d, w) -> apply.run())
          .setNegativeButton(t("cancel"), null)
          .show();
    else apply.run();
  }

  private void runSync(boolean load) {
    if (sync != null) sync.cancel();
    if (load) index.saveScope();
    status.setText(t("loading"));
    sync =
        new CenterSync(
            index,
            e -> {
              error(e);
              status.setText("");
              show(screen);
            });
    if (load) sync.load();
    else sync.discover();
  }

  private void pickPeriod(int i) {
    index.scope.sinceRead = i == 5;
    if (i < 4) {
      index.scope.to = System.currentTimeMillis() / 1000;
      index.scope.from = index.scope.to - new int[] {1, 7, 30, 90}[i] * 86400L;
      index.saveScope();
      show(screen);
    } else if (i == 4)
      input(
          "customDates",
          "2026-11-01 / 2026-11-24",
          dates -> {
            try {
              String[] v = dates.split("/");
              java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
              f.setLenient(false);
              index.scope.from = f.parse(v[0].trim()).getTime() / 1000;
              index.scope.to = f.parse(v[1].trim()).getTime() / 1000 + 86399;
              if (index.scope.to < index.scope.from) throw new Exception();
              index.saveScope();
              show(screen);
            } catch (Exception e) {
              error(t("customDates"));
            }
          });
    else {
      index.scope.to = System.currentTimeMillis() / 1000;
      index.scope.from = reviewFrom(index.scope);
      index.saveScope();
      show(screen);
    }
  }

  private View periodChips() {
    long span = Math.round((index.scope.to - index.scope.from) / 86400.0);
    int selected =
        index.scope.sinceRead
            ? 5
            : span == 1 ? 0 : span == 7 ? 1 : span == 30 ? 2 : span == 90 ? 3 : 4;
    String[] labels = {
      tr("1 day", "1 день"),
      tr("7 days", "7 дней"),
      tr("30 days", "30 дней"),
      tr("90 days", "90 дней"),
      t("customDates"),
      t("sinceRead")
    };
    android.widget.HorizontalScrollView row = ui.chipRow();
    for (int i = 0; i < labels.length; i++) {
      final int pick = i;
      ui.addChip(row, ui.chip(labels[i], i == selected, v -> pickPeriod(pick)));
    }
    return row;
  }

  private LinearLayout filterRow(
      String title,
      String[] labels,
      String[] values,
      String current,
      java.util.function.Consumer<String> set,
      int returnTo) {
    LinearLayout box = new LinearLayout(getParentActivity());
    box.setOrientation(LinearLayout.VERTICAL);
    TextView caption = ui.text(title, 12.5f, ui.sub(), true);
    caption.setPadding(dp(4), dp(12), dp(4), dp(2));
    box.addView(caption);
    android.widget.HorizontalScrollView row = ui.chipRow();
    row.getChildAt(0).setPadding(0, dp(4), 0, dp(4));
    for (int i = 0; i < labels.length; i++) {
      final String value = values[i];
      ui.addChip(
          row,
          ui.chip(
              labels[i],
              value.equals(current),
              v -> {
                set.accept(value);
                show(returnTo);
              }));
    }
    box.addView(row);
    return box;
  }

  private void resetFilters() {
    filter.text = "";
    filter.type = "";
    filter.role = "";
    filter.folder = "";
    filter.status = "";
    filter.activity = "";
    filter.minPerDay = 0;
    filter.accounts.clear();
  }

  private List<CenterData.Source> matchingSources(Map<String, Integer> counts) {
    List<CenterData.Source> matching = new ArrayList<>();
    for (CenterData.Source s : index.sources) {
      Integer n = counts.get(s.key());
      if (filter.matches(s, n == null ? 0 : n, index.scope.from, index.scope.to)) matching.add(s);
    }
    return matching;
  }

  /** Dedicated screen: accounts as cards plus the full source filter. */
  private void accounts() {
    final Map<String, Integer> counts = sourceCounts();
    final List<CenterData.Source> matching = matchingSources(counts);
    LinearLayout filters = ui.card(14, 12);
    filters.addView(ui.text(tr("Source filter", "Фильтр источников"), 16, ui.text(), true));
    TextView hint =
        ui.text(
            tr(
                "Filters combine: a source is shown only if it matches all of them.",
                "Фильтры складываются: источник виден, только если подходит под все."),
            12.5f,
            ui.sub(),
            false);
    hint.setPadding(0, dp(2), 0, dp(10));
    filters.addView(hint);
    EditText search = ui.field(t("search"), filter.text, 1);
    search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
    search.setOnEditorActionListener(
        (v, action, event) -> {
          filter.text = v.getText().toString().trim();
          show(9);
          return true;
        });
    filters.addView(search, new LinearLayout.LayoutParams(-1, -2));
    filters.addView(
        filterRow(
            t("type"),
            new String[] {
              t("all"), t("group"), t("supergroup"), t("channel"), t("topic"), t("privateChats")
            },
            new String[] {"", "group", "supergroup", "channel", "topic", "private"},
            filter.type,
            v -> filter.type = v,
            9));
    filters.addView(
        filterRow(
            t("role"),
            new String[] {t("all"), t("owner"), t("admin"), t("member")},
            new String[] {"", "owner", "admin", "member"},
            filter.role,
            v -> filter.role = v,
            9));
    filters.addView(
        filterRow(
            t("status"),
            new String[] {t("all"), t("pinned"), t("archived"), t("muted")},
            new String[] {"", "pinned", "archive", "muted"},
            filter.status,
            v -> filter.status = v,
            9));
    filters.addView(
        filterRow(
            t("active"),
            new String[] {t("all"), t("active"), t("quiet")},
            new String[] {"", "active", "quiet"},
            filter.activity,
            v -> filter.activity = v,
            9));
    android.widget.HorizontalScrollView more = ui.chipRow();
    more.getChildAt(0).setPadding(0, dp(12), 0, dp(2));
    ui.addChip(
        more,
        ui.chip(
            t("folder") + (filter.folder.isEmpty() ? "" : ": " + filter.folder),
            !filter.folder.isEmpty(),
            v ->
                input(
                    "folder",
                    filter.folder,
                    value -> {
                      filter.folder = value;
                      show(9);
                    })));
    ui.addChip(
        more,
        ui.chip(
            t("minActivity") + (filter.minPerDay > 0 ? ": " + filter.minPerDay : ""),
            filter.minPerDay > 0,
            v ->
                input(
                    "minActivity",
                    "" + filter.minPerDay,
                    value -> {
                      try {
                        filter.minPerDay = Math.max(0, Integer.parseInt(value));
                        show(9);
                      } catch (Exception e) {
                        error(e.getMessage());
                      }
                    })));
    filters.addView(more);
    filters.addView(ui.divider());
    LinearLayout foot = new LinearLayout(getParentActivity());
    foot.setGravity(Gravity.CENTER_VERTICAL);
    TextView found =
        ui.text(
            t("count") + ": " + matching.size() + " / " + index.sources.size(),
            14,
            ui.text(),
            true);
    foot.addView(found, new LinearLayout.LayoutParams(0, -2, 1));
    foot.addView(
        ui.chip(
            tr("Reset", "Сбросить"),
            false,
            v -> {
              resetFilters();
              show(9);
            }));
    foot.addView(
        ui.chip(
            tr("Show list", "К списку"),
            true,
            v -> {
              show(0);
            }),
        margins(-2, -2, 8, 0, 0, 0));
    filters.addView(foot);
    body.addView(filters, ui.cardParams());

    List<Integer> accounts = activeAccounts();
    body.addView(
        ui.section(tr("Accounts", "Аккаунты") + " · " + accounts.size()));
    if (accounts.isEmpty())
      body.addView(
          ui.empty(
              R.drawable.tp_aic_accounts,
              tr("No accounts", "Нет аккаунтов"),
              tr("Sign in to a Telegram account first.", "Сначала войдите в аккаунт Telegram.")));
    for (int a : accounts) {
      final int account = a;
      long n = index.sources.stream().filter(s -> s.account == account).count();
      long loaded = index.messages.stream().filter(m -> m.account == account).count();
      long groupsCount =
          index.sources.stream()
              .filter(s -> s.account == account && !s.type.equals("channel") && !s.type.equals("private"))
              .count();
      long channelsCount =
          index.sources.stream().filter(s -> s.account == account && s.type.equals("channel")).count();
      LinearLayout card = ui.card(14, 12);
      LinearLayout head = new LinearLayout(getParentActivity());
      head.setGravity(Gravity.CENTER_VERTICAL);
      avatar(head, account, UserConfig.getInstance(account).getClientUserId());
      LinearLayout col = new LinearLayout(getParentActivity());
      col.setOrientation(LinearLayout.VERTICAL);
      TextView name = ui.text(accountTitle(account), 16, ui.text(), true);
      name.setSingleLine(true);
      name.setEllipsize(android.text.TextUtils.TruncateAt.END);
      col.addView(name);
      TextView sub =
          ui.text(
              tr("Account", "Аккаунт") + " " + (account + 1) + " · " + t("session") + ": " + t("available"),
              12.5f,
              ui.sub(),
              false);
      col.addView(sub);
      head.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
      head.addView(ui.pill(modeTitle(accountMode(account)), modeColor(accountMode(account))));
      card.addView(head);
      card.addView(
          ui.kpiRow(
              ui.kpi("" + n, t("sources"), ui.series(0), groupsCount + " · " + channelsCount, ui.sub()),
              ui.kpi("" + loaded, t("messages"), ui.series(1), tr("loaded", "загружено"), ui.sub())),
          margins(-1, -2, 0, 4, 0, 4));
      TextView scopeLabel = ui.text(tr("Analysis scope", "Охват анализа"), 12.5f, ui.sub(), true);
      scopeLabel.setPadding(dp(4), dp(8), dp(4), dp(4));
      card.addView(scopeLabel);
      card.addView(
          ui.segmented(
              new String[] {t("all"), t("selected"), t("excluded")},
              accountMode(account),
              mode -> applyAccountMode(account, mode)));
      boolean shown = filter.accounts.isEmpty() || filter.accounts.contains(account);
      LinearLayout list = new LinearLayout(getParentActivity());
      list.setGravity(Gravity.CENTER_VERTICAL);
      list.setPadding(dp(4), dp(12), dp(4), 0);
      TextView listTitle = ui.text(tr("Show in source list", "Показывать в списке источников"), 14, ui.text(), false);
      list.addView(listTitle, new LinearLayout.LayoutParams(0, -2, 1));
      list.addView(
          ui.switchView(
              shown,
              on -> {
                if (filter.accounts.isEmpty()) filter.accounts.addAll(accounts);
                if (on) filter.accounts.add(account);
                else filter.accounts.remove(account);
                if (filter.accounts.containsAll(accounts)) filter.accounts.clear();
                show(9);
              }));
      card.addView(list);
      LinearLayout actions = new LinearLayout(getParentActivity());
      actions.setPadding(0, dp(10), 0, 0);
      actions.addView(
          ui.chip(
              tr("Only this account", "Только этот аккаунт"),
              false,
              v -> {
                filter.accounts.clear();
                filter.accounts.add(account);
                show(0);
              }));
      actions.addView(
          ui.chip(
              tr("Sources", "Источники") + " ›",
              false,
              v -> {
                filter.accounts.clear();
                filter.accounts.add(account);
                show(0);
              }),
          margins(-2, -2, 8, 0, 0, 0));
      card.addView(actions);
      body.addView(card, ui.cardParams());
    }
  }

  private void sources() {
    final Map<String, Integer> counts = sourceCounts();
    final List<CenterData.Source> matching = matchingSources(counts);
    final List<Integer> accounts = activeAccounts();

    LinearLayout controls = ui.card(6, 12);
    TextView periodTitle = ui.text(tr("Period", "Период"), 12.5f, ui.sub(), true);
    periodTitle.setPadding(dp(10), 0, dp(10), dp(2));
    controls.addView(periodTitle);
    controls.addView(periodChips());
    TextView refresh = ui.button(t("refresh"), false, R.drawable.tp_aic_refresh, v -> runSync(false));
    TextView load = ui.button(t("load"), true, R.drawable.tp_aic_download, v -> runSync(true));
    controls.addView(ui.buttonRow(refresh, load), margins(-1, -2, 0, 8, 0, 0));
    if (status.getText().length() > 0)
      controls.addView(
          ui.buttonRow(
              ui.button(
                  t("cancel"),
                  false,
                  R.drawable.tp_aic_close,
                  v -> {
                    if (sync != null) sync.cancel();
                    status.setText("");
                    reload();
                  })));
    body.addView(controls, ui.cardParams());

    LinearLayout accountCard =
        ui.clickableCard(12, 10, v -> show(9));
    LinearLayout accountHead = new LinearLayout(getParentActivity());
    accountHead.setGravity(Gravity.CENTER_VERTICAL);
    accountHead.addView(
        ui.text(tr("Accounts", "Аккаунты") + " · " + accounts.size(), 15, ui.text(), true),
        new LinearLayout.LayoutParams(0, -2, 1));
    accountHead.addView(ui.text(tr("Accounts and filters", "Аккаунты и фильтры") + " ›", 13, ui.accent(), true));
    accountCard.addView(accountHead);
    android.widget.HorizontalScrollView strip = new android.widget.HorizontalScrollView(getParentActivity());
    strip.setHorizontalScrollBarEnabled(false);
    LinearLayout stripRow = new LinearLayout(getParentActivity());
    for (int a : accounts) {
      final int account = a;
      LinearLayout mini = new LinearLayout(getParentActivity());
      mini.setOrientation(LinearLayout.VERTICAL);
      mini.setGravity(Gravity.CENTER_HORIZONTAL);
      mini.setPadding(dp(4), dp(8), dp(4), dp(2));
      LinearLayout face = new LinearLayout(getParentActivity());
      avatar(face, account, UserConfig.getInstance(account).getClientUserId());
      mini.addView(face, new LinearLayout.LayoutParams(-2, -2));
      TextView n = ui.text(accountTitle(account), 12, ui.text(), false);
      n.setSingleLine(true);
      n.setMaxWidth(dp(92));
      n.setEllipsize(android.text.TextUtils.TruncateAt.END);
      n.setGravity(Gravity.CENTER);
      mini.addView(n);
      TextView state = ui.pill(modeTitle(accountMode(account)), modeColor(accountMode(account)));
      mini.addView(state, margins(-2, -2, 0, 4, 0, 0));
      LinearLayout.LayoutParams miniParams = new LinearLayout.LayoutParams(dp(100), -2);
      miniParams.rightMargin = dp(4);
      stripRow.addView(mini, miniParams);
    }
    strip.addView(stripRow);
    accountCard.addView(strip);
    body.addView(accountCard, ui.cardParams());

    LinearLayout searchCard = ui.card(12, 10);
    LinearLayout searchRow = new LinearLayout(getParentActivity());
    searchRow.setGravity(Gravity.CENTER_VERTICAL);
    EditText search = ui.field(t("search"), filter.text, 1);
    search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
    search.setOnEditorActionListener(
        (v, action, event) -> {
          filter.text = v.getText().toString().trim();
          show(0);
          return true;
        });
    searchRow.addView(search, new LinearLayout.LayoutParams(0, -2, 1));
    searchRow.addView(
        ui.button(tr("Filters", "Фильтры"), false, R.drawable.tp_aic_filter, v -> show(9)),
        margins(-2, -2, 8, 0, 0, 0));
    searchCard.addView(searchRow);
    List<String> active = new ArrayList<>();
    if (!filter.type.isEmpty()) active.add(t("type") + ": " + t(filter.type.equals("private") ? "privateChats" : filter.type));
    if (!filter.role.isEmpty()) active.add(t("role") + ": " + t(filter.role));
    if (!filter.status.isEmpty()) active.add(t("status") + ": " + t(filter.status.equals("archive") ? "archived" : filter.status));
    if (!filter.activity.isEmpty()) active.add(t("active") + ": " + t(filter.activity));
    if (!filter.folder.isEmpty()) active.add(t("folder") + ": " + filter.folder);
    if (filter.minPerDay > 0) active.add(t("minActivity") + ": " + filter.minPerDay);
    if (!filter.accounts.isEmpty()) active.add(t("account") + ": " + filter.accounts.size());
    if (!active.isEmpty()) {
      android.widget.HorizontalScrollView activeRow = ui.chipRow();
      activeRow.getChildAt(0).setPadding(0, dp(8), 0, 0);
      for (String label : active)
        ui.addChip(
            activeRow,
            ui.chip(
                label + "  ✕",
                true,
                v -> {
                  resetFilters();
                  show(0);
                }));
      searchCard.addView(activeRow);
    }
    body.addView(searchCard, ui.cardParams());

    toggle(
        "own",
        index.scope.excludeOwn,
        b -> {
          index.scope.excludeOwn = b;
          index.saveScope();
          show(0);
        });
    toggle(
        "privateChats",
        index.scope.includePrivate,
        b -> {
          if (b)
            dialogBuilder()
                .setMessage(t("privateChats"))
                .setPositiveButton(
                    t("confirm"),
                    (d, w) -> {
                      index.scope.includePrivate = true;
                      index.saveScope();
                      show(0);
                    })
                .setNegativeButton(t("cancel"), (d, w) -> show(0))
                .show();
          else {
            index.scope.includePrivate = false;
            index.saveScope();
            show(0);
          }
        });

    body.addView(
        ui.section(
            t("count") + " " + matching.size() + " / " + index.sources.size() + " · " + index.selected().size() + " " + t("messages")));
    android.widget.HorizontalScrollView bulk = ui.chipRow();
    ui.addChip(
        bulk,
        ui.chip(
            t("selectAll"),
            false,
            v -> {
              for (CenterData.Source s : matching) {
                index.scope.excluded.remove(s.account);
                index.scope.sources.add(s.key());
              }
              index.saveScope();
              show(0);
            }));
    ui.addChip(
        bulk,
        ui.chip(
            t("excludeMatching"),
            false,
            v -> {
              for (CenterData.Source s : matching) {
                if (index.scope.whole.remove(s.account))
                  for (CenterData.Source q : index.sources)
                    if (q.account == s.account) index.scope.sources.add(q.key());
                index.scope.sources.remove(s.key());
              }
              index.saveScope();
              show(0);
            }));
    ui.addChip(
        bulk,
        ui.chip(
            t("saveSet"),
            false,
            v ->
                input(
                    "name",
                    "",
                    name -> {
                      try {
                        TPAiStore.putPrivate("center_set_" + name, index.scope.json().toString());
                        JSONObject sets = CenterIndex.settings().optJSONObject("sets");
                        if (sets == null) sets = new JSONObject();
                        sets.put(name, true);
                        CenterIndex.setting("sets", sets);
                      } catch (Exception e) {
                        error(e.getMessage());
                      }
                    })));
    ui.addChip(
        bulk,
        ui.chip(
            t("sets"),
            false,
            v -> {
              JSONObject o = CenterIndex.settings().optJSONObject("sets");
              ArrayList<String> names = new ArrayList<>();
              if (o != null) {
                Iterator<String> keys = o.keys();
                while (keys.hasNext()) names.add(keys.next());
              }
              choices(
                  "sets",
                  names.toArray(new String[0]),
                  i -> {
                    try {
                      index.scope =
                          CenterData.Scope.parse(
                              new JSONObject(TPAiStore.getPrivate("center_set_" + names.get(i))));
                      index.saveScope();
                      show(0);
                    } catch (Exception e) {
                      error(e.getMessage());
                    }
                  });
            }));
    body.addView(bulk);

    if (index.sources.isEmpty()) {
      body.addView(
          ui.empty(
              R.drawable.tp_aic_sources,
              tr("No sources yet", "Источников пока нет"),
              tr(
                  "Tap “Refresh” to find groups and channels of all your accounts.",
                  "Нажмите «Обновить» — центр найдёт группы и каналы всех ваших аккаунтов.")));
      return;
    }
    if (matching.isEmpty()) {
      body.addView(
          ui.empty(
              R.drawable.tp_aic_filter,
              tr("Nothing matches", "Ничего не найдено"),
              tr("Loosen or reset the filters.", "Ослабьте или сбросьте фильтры.")));
      return;
    }
    int max = 1;
    for (CenterData.Source s : matching) {
      Integer n = counts.get(s.key());
      if (n != null) max = Math.max(max, n);
    }
    int shownCount = 0;
    for (int a : accounts) {
      List<CenterData.Source> own = new ArrayList<>();
      for (CenterData.Source s : matching) if (s.account == a) own.add(s);
      if (own.isEmpty() || shownCount >= sourceLimit) continue;
      if (accounts.size() > 1) {
        LinearLayout header = new LinearLayout(getParentActivity());
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(12), dp(8), dp(0));
        avatar(header, a, UserConfig.getInstance(a).getClientUserId());
        header.addView(
            ui.text(accountTitle(a) + " · " + own.size(), 14, ui.text(), true),
            new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(header);
      }
      for (CenterData.Source s : own) {
        if (shownCount++ >= sourceLimit) break;
        Integer n = counts.get(s.key());
        body.addView(sourceRow(s, n == null ? 0 : n, max), margins(-1, -2, 12, 3, 12, 3));
      }
    }
    if (matching.size() > sourceLimit)
      button(
          "next",
          () -> {
            sourceLimit += 100;
            show(0);
          });
  }

  private View sourceRow(CenterData.Source s, int count, int max) {
    LinearLayout r = new LinearLayout(getParentActivity());
    r.setGravity(Gravity.CENTER_VERTICAL);
    r.setPadding(dp(2), dp(4), dp(12), dp(4));
    r.setBackground(ui.ripple(ui.shape(ui.card(), 14, ui.line(), 1), 14));
    avatar(r, s.account, s.dialog);
    LinearLayout col = new LinearLayout(getParentActivity());
    col.setOrientation(LinearLayout.VERTICAL);
    TextView title = ui.text(s.title, 15, ui.text(), true);
    title.setSingleLine(true);
    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    col.addView(title);
    StringBuilder line = new StringBuilder(t(s.type));
    if (!s.role.isEmpty()) line.append(" · ").append(t(s.role));
    if (!s.folder.isEmpty()) line.append(" · ").append(s.folder);
    if (s.pinned) line.append(" · ").append(t("pinned"));
    if (s.archived) line.append(" · ").append(t("archived"));
    if (s.muted) line.append(" · ").append(t("muted"));
    TextView sub = ui.text(line.toString(), 12, ui.sub(), false);
    sub.setSingleLine(true);
    sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
    col.addView(sub);
    LinearLayout activity = new LinearLayout(getParentActivity());
    activity.setGravity(Gravity.CENTER_VERTICAL);
    activity.setPadding(0, dp(5), 0, 0);
    activity.addView(
        ui.meter(count / (float) max, count == 0 ? ui.sub() : ui.accent(), 5),
        new LinearLayout.LayoutParams(0, dp(5), 1));
    TextView number = ui.text(count + " " + t("messages"), 11.5f, ui.sub(), false);
    number.setPadding(dp(8), 0, 0, 0);
    activity.addView(number);
    col.addView(activity);
    r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
    Switch toggle =
        ui.switchView(
            index.scope.allows(s),
            on -> {
              if (index.scope.whole.remove(s.account)) {
                for (CenterData.Source q : index.sources)
                  if (q.account == s.account) index.scope.sources.add(q.key());
              }
              index.scope.excluded.remove(s.account);
              if (on) index.scope.sources.add(s.key());
              else index.scope.sources.remove(s.key());
              index.saveScope();
              scopeLine.setText(scopeSummary());
            });
    r.addView(toggle, margins(-2, -2, 8, 0, 0, 0));
    r.setOnClickListener(w -> toggle.toggle());
    return r;
  }

  private String defaultQuestion(int kind) {
    switch (kind) {
      case 1:
        return tr(
            "Who published what, and what matters in those posts?",
            "Кто что публиковал и что важно в этих публикациях?");
      case 2:
        return tr(
            "What is new since I last reviewed these groups?",
            "Что нового с моего последнего просмотра этих групп?");
      case 3:
        return tr(
            "What changed compared with the previous period?",
            "Что изменилось по сравнению с предыдущим периодом?");
      default:
        return tr(
            "What was discussed and what matters most?", "Что обсуждалось и что важнее всего?");
    }
  }

  private View kindOption(int kind, String title, String subtitle) {
    boolean on = reportKind == kind;
    LinearLayout r = new LinearLayout(getParentActivity());
    r.setGravity(Gravity.CENTER_VERTICAL);
    r.setPadding(dp(14), dp(10), dp(14), dp(10));
    r.setBackground(
        ui.ripple(
            on
                ? ui.shape(TelePremiumCenterUi.alpha(ui.accent(), 22), 14, ui.accent(), 1.5f)
                : ui.shape(ui.card(), 14, ui.line(), 1),
            14));
    LinearLayout col = new LinearLayout(getParentActivity());
    col.setOrientation(LinearLayout.VERTICAL);
    col.addView(ui.text(title, 15, ui.text(), true));
    TextView sub = ui.text(subtitle, 12.5f, ui.sub(), false);
    sub.setPadding(0, dp(2), 0, 0);
    col.addView(sub);
    r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
    if (on) r.addView(ui.icon(R.drawable.tp_aic_check, ui.accent(), 22));
    r.setOnClickListener(
        v -> {
          reportKind = kind;
          show(1);
        });
    return r;
  }

  private String focusDraft = "";

  /** "Сводка": pick what to collect, press one button, read the result with proof. */
  private void summary() {
    body.addView(ui.section(tr("What to collect", "Что собрать")));
    String[][] kinds = {
      {
        tr("Summary of the groups", "Сводка по группам"),
        tr("What was discussed and what matters", "Что обсуждали и что важно")
      },
      {
        tr("Who published what", "Кто что публиковал"),
        tr("Posts of each author, with quotes and pictures", "Публикации авторов с цитатами и картинками")
      },
      {
        tr("What is new", "Что нового"),
        tr("Only what you have not reviewed yet", "Только то, что вы ещё не просматривали")
      },
      {
        tr("Compare with the previous period", "Сравнить с прошлым периодом"),
        tr("What changed against the same-length period before", "Что изменилось по сравнению с таким же периодом до этого")
      }
    };
    for (int i = 0; i < kinds.length; i++)
      body.addView(kindOption(i, kinds[i][0], kinds[i][1]), margins(-1, -2, 12, 3, 12, 3));

    LinearLayout box = ui.card(14, 12);
    box.addView(ui.text(tr("Focus (optional)", "Уточнение (необязательно)"), 12.5f, ui.sub(), true));
    final EditText focus =
        ui.field(
            tr("For example: decisions, deadlines, disputes", "Например: решения, сроки, споры"),
            focusDraft,
            2);
    focus.addTextChangedListener(
        new android.text.TextWatcher() {
          public void beforeTextChanged(CharSequence c, int a, int b, int d) {}

          public void onTextChanged(CharSequence c, int a, int b, int d) {
            focusDraft = c.toString();
          }

          public void afterTextChanged(android.text.Editable e) {}
        });
    box.addView(focus, margins(-1, -2, 0, 6, 0, 0));
    box.addView(ui.divider());
    box.addView(ui.text(tr("Format", "Формат"), 12.5f, ui.sub(), true));
    LinearLayout seg =
        ui.segmented(
            new String[] {tr("Short", "Кратко"), tr("Detailed", "Подробно")},
            detailed ? 1 : 0,
            picked -> detailed = picked == 1);
    box.addView(seg, margins(-1, -2, 0, 6, 0, 0));
    LinearLayout switches = new LinearLayout(getParentActivity());
    switches.setOrientation(LinearLayout.VERTICAL);
    switches.setPadding(0, dp(8), 0, 0);
    final Switch pictures =
        ui.switchView(
            exportOptions().images,
            on -> {
              CenterExport.Options options = exportOptions();
              options.images = on;
              saveExportOptions(options);
            });
    switches.addView(
        switchLine(
            tr("Add pictures", "Добавить картинки"),
            tr("Show images from the messages next to the points", "Показывать изображения из сообщений рядом с пунктами"),
            pictures));
    final Switch personal =
        ui.switchView(
            index.scope.includePersonal,
            on -> {
              index.scope.includePersonal = on;
              index.saveScope();
            });
    switches.addView(
        switchLine(
            tr("Include personal messages", "Учитывать личные сообщения"),
            tr("Only if you chose private chats among the sources", "Только если среди источников выбраны личные чаты"),
            personal));
    box.addView(switches);
    final TextView ask =
        ui.button(
            reportRunning ? t("cancel") : tr("Collect summary", "Собрать сводку"),
            !reportRunning,
            reportRunning ? R.drawable.tp_aic_close : R.drawable.tp_aic_summaries,
            v -> {
              if (reportRunning) {
                aiCancelled.set(true);
                reportRunning = false;
                show(1);
                return;
              }
              String extra = focusDraft.trim();
              query = defaultQuestion(reportKind) + (extra.isEmpty() ? "" : " " + extra);
              runReport(query);
            });
    box.addView(ask, margins(-1, -2, 0, 14, 0, 0));
    body.addView(box, ui.cardParams());
    reportSection(false);
  }

  private LinearLayout switchLine(String title, String subtitle, Switch sw) {
    LinearLayout line = new LinearLayout(getParentActivity());
    line.setGravity(Gravity.CENTER_VERTICAL);
    line.setPadding(dp(2), dp(8), dp(2), dp(8));
    LinearLayout col = new LinearLayout(getParentActivity());
    col.setOrientation(LinearLayout.VERTICAL);
    col.addView(ui.text(title, 14.5f, ui.text(), false));
    TextView sub = ui.text(subtitle, 12, ui.sub(), false);
    sub.setPadding(0, dp(2), dp(8), 0);
    col.addView(sub);
    line.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
    line.addView(sw);
    line.setOnClickListener(w -> sw.toggle());
    return line;
  }

  private String draft = "";
  private CenterReports.Report summaryReport, analystReport;

  private void openTab(int tab) {
    if (screen == 1 && tab != 1) summaryReport = report;
    else if (screen == 6 && tab != 6) analystReport = report;
    if (tab == 1 && screen != 1) report = summaryReport;
    else if (tab == 6 && screen != 6) report = analystReport;
    drilldown = tab == 6 ? drilldown : null;
    authorReport = tab == 6 && authorReport;
    show(tab);
  }

  /** "Аналитик": a conversation. The answer is above, the input is docked at the bottom. */
  private void analyst() {
    if (!authorReport) reportKind = 0;
    if (report == null) {
      LinearLayout empty =
          ui.empty(
              R.drawable.tp_aic_analyst,
              tr("Ask about your groups", "Спросите про ваши группы"),
              tr(
                  "Ask any question. The analyst answers only from the selected sources and shows the messages that prove each point.",
                  "Задайте любой вопрос. Аналитик отвечает только по выбранным источникам и показывает сообщения, подтверждающие каждый пункт."));
      body.addView(empty);
      String[] examples =
          org.telegram.messenger.telepremium.TPStrings.isRussian()
              ? new String[] {
                "Какие решения приняты?",
                "Кто о чём спорил?",
                "Какие вопросы остались без ответа?",
                "Что обещали сделать и к какому сроку?"
              }
              : new String[] {
                "Which decisions were made?",
                "Who argued about what?",
                "Which questions stayed unanswered?",
                "What was promised and by when?"
              };
      LinearLayout list = new LinearLayout(getParentActivity());
      list.setOrientation(LinearLayout.VERTICAL);
      for (String example : examples) {
        TextView row = ui.chip(example, false, v -> {
          draft = example;
          show(6);
        });
        row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        list.addView(row, margins(-1, -2, 16, 4, 16, 4));
      }
      body.addView(list);
    }
    reportSection(true);
    buildDock();
  }

  private void buildDock() {
    dock.setVisibility(View.VISIBLE);
    View line = new View(getParentActivity());
    line.setBackgroundColor(ui.line());
    dock.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f))));
    android.widget.HorizontalScrollView options = ui.chipRow();
    options.getChildAt(0).setPadding(dp(10), dp(8), dp(10), dp(2));
    final TextView shortChip = ui.chip(tr("Short", "Кратко"), !detailed, null);
    final TextView detailedChip = ui.chip(tr("Detailed", "Подробно"), detailed, null);
    shortChip.setOnClickListener(
        v -> {
          detailed = false;
          ui.restyleChip(shortChip, true);
          ui.restyleChip(detailedChip, false);
        });
    detailedChip.setOnClickListener(
        v -> {
          detailed = true;
          ui.restyleChip(shortChip, false);
          ui.restyleChip(detailedChip, true);
        });
    ui.addChip(options, shortChip);
    ui.addChip(options, detailedChip);
    ui.addChip(
        options,
        ui.toggleChip(
            tr("With pictures", "С картинками"),
            exportOptions().images,
            on -> {
              CenterExport.Options o = exportOptions();
              o.images = on;
              saveExportOptions(o);
            }));
    ui.addChip(
        options,
        ui.toggleChip(
            t("personal"),
            index.scope.includePersonal,
            on -> {
              index.scope.includePersonal = on;
              index.saveScope();
            }));
    ui.addChip(options, ui.chip(tr("History", "История"), false, v -> history()));
    dock.addView(options);
    LinearLayout inputRow = new LinearLayout(getParentActivity());
    inputRow.setGravity(Gravity.BOTTOM);
    inputRow.setPadding(dp(10), dp(4), dp(10), dp(10));
    final EditText input = ui.field(tr("Ask a question…", "Задайте вопрос…"), draft, 4);
    input.addTextChangedListener(
        new android.text.TextWatcher() {
          public void beforeTextChanged(CharSequence c, int a, int b, int d) {}

          public void onTextChanged(CharSequence c, int a, int b, int d) {
            draft = c.toString();
          }

          public void afterTextChanged(android.text.Editable e) {}
        });
    inputRow.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
    FrameLayout send = ui.badge(reportRunning ? R.drawable.tp_aic_close : R.drawable.tp_aic_send, ui.onAccent(), 46, 22);
    send.setBackground(ui.shape(reportRunning ? ui.bad() : ui.accent(), 23, 0, 0));
    send.setOnClickListener(
        v -> {
          if (reportRunning) {
            aiCancelled.set(true);
            reportRunning = false;
            show(6);
            return;
          }
          String question = input.getText().toString().trim();
          if (question.isEmpty()) {
            toast(t("query"));
            return;
          }
          query = question;
          draft = "";
          input.setText("");
          AndroidUtilities.hideKeyboard(input);
          runReport(question);
        });
    LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(46), dp(46));
    sendParams.leftMargin = dp(8);
    inputRow.addView(send, sendParams);
    dock.addView(inputRow);
  }

  private void reportSection(boolean chat) {
    android.widget.HorizontalScrollView tools = ui.chipRow();
    ui.addChip(
        tools,
        ui.chip(
            t("templates"),
            false,
            v -> {
              JSONObject templates = CenterIndex.settings().optJSONObject("templates");
              List<String> names = new ArrayList<>();
              if (templates != null) {
                Iterator<String> keys = templates.keys();
                while (keys.hasNext()) names.add(keys.next());
              }
              JSONObject saved = templates;
              choices(
                  "templates",
                  names.toArray(new String[0]),
                  i -> {
                    try {
                      JSONObject value = saved.getJSONObject(names.get(i));
                      query = value.optString("query");
                      draft = query;
                      index.scope = CenterData.Scope.parse(value.getJSONObject("scope"));
                      index.saveScope();
                      show(screen);
                    } catch (Exception e) {
                      error(e.getMessage());
                    }
                  });
            }));
    ui.addChip(
        tools,
        ui.chip(
            t("markReviewed"),
            false,
            v -> {
              long now = System.currentTimeMillis();
              for (CenterData.Source source : index.sources)
                if (index.scope.allows(source)) {
                  source.readAt = now;
                  CenterStore.IO.execute(
                      () -> {
                        try {
                          CenterStore.get()
                              .put(
                                  source.account,
                                  source.owner,
                                  "source",
                                  source.key(),
                                  source.json());
                        } catch (Exception e) {
                          FileLog.e(e);
                        }
                      });
                }
              toast(t("done"));
            }));
    if (!chat) body.addView(tools, margins(-1, -2, 8, 0, 8, 0));
    if (report != null) {
      renderReport(report);
      android.widget.HorizontalScrollView actions = ui.chipRow();
      ui.addChip(
          actions,
          ui.chip(
              t("archive"),
              true,
              v -> persistReport(report, "report")));
      ui.addChip(
          actions,
          ui.chip(
              t("saveTemplate"),
              false,
              v ->
                  input(
                      "name",
                      "",
                      name -> {
                        try {
                          JSONObject ts = CenterIndex.settings().optJSONObject("templates");
                          if (ts == null) ts = new JSONObject();
                          ts.put(
                              name,
                              new JSONObject()
                                  .put("query", query)
                                  .put("scope", index.scope.json()));
                          CenterIndex.setting("templates", ts);
                        } catch (Exception err) {
                          error(err.getMessage());
                        }
                      })));
      ui.addChip(
          actions,
          ui.chip(
              t("onlyAuthor"),
              false,
              v -> {
                Map<Long, String> names = new LinkedHashMap<>();
                for (CenterReports.Claim claim : report.claims)
                  for (CenterReports.Evidence proof : claim.evidence)
                    if (proof.message.author != 0)
                      names.put(proof.message.author, proof.message.authorName);
                List<Long> ids = new ArrayList<>(names.keySet());
                choices(
                    "authors",
                    names.values().toArray(new String[0]),
                    which -> {
                      index.scope.authors.clear();
                      index.scope.authors.add(ids.get(which));
                      index.saveScope();
                      drilldown = null;
                      authorReport = true;
                      show(6);
                    });
              }));
      ui.addChip(
          actions,
          ui.chip(
              t("compare"),
              false,
              v -> {
                reportKind = 3;
                drilldown = null;
                runReport(report.question);
              }));
      body.addView(actions, margins(-1, -2, 8, 4, 8, 0));
    } else if (!chat) {
      body.addView(
          ui.empty(
              R.drawable.tp_aic_summaries,
              tr("No report yet", "Отчёта пока нет"),
              tr(
                  "Pick the groups on the Sources screen, then ask a question above.",
                  "Выберите группы на экране «Источники» и задайте вопрос выше.")));
    }
  }

  private long reviewFrom(CenterData.Scope scope) {
    long cutoff =
        System.currentTimeMillis() / 1000
            - CenterIndex.settings().optInt("retentionDays", 90) * 86400L;
    return CenterData.reviewFrom(index.sources, scope, cutoff, scope.to);
  }

  private void runReport(String question) {
    runReport(question, false);
  }

  private void runReport(String question, boolean prepared) {
    if (reportRunning) {
      toast(t("queue"));
      return;
    }
    CenterData.Scope requested;
    try {
      requested = CenterData.Scope.parse(index.scope.json());
    } catch (Exception error) {
      error(error.getMessage());
      return;
    }
    if (reportKind == 2) {
      requested.sinceRead = true;
      requested.from = reviewFrom(requested);
    } else if (reportKind == 3) {
      requested.from -= Math.max(1, requested.to - requested.from + 1);
    }
    if (!prepared
        && drilldown == null
        && index.sources.stream()
            .anyMatch(
                source ->
                    requested.requests(source) && !source.covers(requested.from, requested.to))) {
      CenterIndex loading = new CenterIndex();
      loading.sources.addAll(index.sources);
      loading.messages.addAll(index.messages);
      loading.scope = requested;
      status.setText(t("loading"));
      sync =
          new CenterSync(
              loading,
              failure -> {
                error(failure);
                index.load(
                    loaded -> {
                      error(loaded);
                      runReport(question, true);
                    });
              });
      sync.load();
      return;
    }
    Runnable action =
        () -> {
          if (CenterIndex.settings().optBoolean("localOnly", false)) {
            toast(t("local"));
            return;
          }
          List<CenterData.Message> selected =
              drilldown == null
                  ? index.selected()
                  : CenterData.select(drilldown, index.sources, index.scope);
          if (selected.isEmpty()) {
            toast(t("empty"));
            return;
          }
          CenterData.Scope scope;
          try {
            scope = CenterData.Scope.parse(index.scope.json());
          } catch (Exception e) {
            error(e.getMessage());
            return;
          }
          List<CenterData.Source> sources = new ArrayList<>(index.sources);
          String request = question;
          if (reportKind == 1)
            request +=
                "\n"
                    + "Report by author across the selected groups, based only on public"
                    + " publications; no personality inference.";
          else if (reportKind == 2) {
            scope.sinceRead = true;
            scope.from = reviewFrom(scope);
            selected = CenterData.select(index.readable(), sources, scope);
          } else if (reportKind == 3) {
            long span = Math.max(1, scope.to - scope.from + 1);
            scope.from -= span;
            selected = CenterData.select(index.messages, sources, scope);
            request +=
                "\n"
                    + "Compare the two equal adjacent periods. Explicitly identify insufficient"
                    + " previous-period coverage.";
          }
          final List<CenterData.Message> data = selected;
          final String prompt = request;
          int chars = 0;
          for (CenterData.Message m : data) chars += m.searchable().length();
          String estimate =
              t("estimate")
                  + ": "
                  + data.size()
                  + " · ≈"
                  + ((chars + 2) / 3 + ((chars + 17999) / 18000) * 2500 + 4000)
                  + " · "
                  + t("usage");
          dialogBuilder()
              .setTitle(t("estimate"))
              .setMessage(estimate)
              .setPositiveButton(
                  t("continue"),
                  (d, w) -> {
                    reportRunning = true;
                    aiCancelled.set(false);
                    status.setText(t("loading"));
                    final boolean ephemeral = authorReport || reportKind == 1;
                    TPAiClient.async(
                        () ->
                            CenterTaskService.trackReport(
                                data,
                                sources,
                                scope,
                                prompt,
                                detailed,
                                ephemeral,
                                aiCancelled,
                                (n, total) ->
                                    AndroidUtilities.runOnUIThread(
                                        () -> {
                                          if (unlocked && foreground)
                                            status.setText(
                                                t("progress") + ": " + n + " / " + total);
                                        })),
                        (result, e) -> {
                          reportRunning = false;
                          if (!unlocked || !foreground || aiCancelled.get()) return;
                          status.setText("");
                          if (e != null) {
                            error(e);
                            return;
                          }
                          report = result;
                          reportPage = 0;
                          show(screen);
                        });
                  })
              .setNegativeButton(t("cancel"), null)
              .show();
        };
    if (CenterIndex.settings().optBoolean("consent", false)) action.run();
    else
      dialogBuilder()
          .setMessage(t("consent"))
          .setPositiveButton(
              t("confirm"),
              (d, w) -> {
                CenterIndex.setting("consent", true);
                action.run();
              })
          .setNegativeButton(t("cancel"), null)
          .show();
  }

  private String dateTime(long millis) {
    return java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        .format(new Date(millis));
  }

  private String dateOnly(long seconds) {
    return java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
        .format(new Date(seconds * 1000L));
  }

  private void renderReport(CenterReports.Report r) {
    LinearLayout summary = ui.card(16, 14);
    TextView question = ui.text(r.question, 17, ui.text(), true);
    summary.addView(question);
    TextView period =
        ui.text(
            dateOnly(r.from) + " — " + dateOnly(r.to) + " · " + dateTime(r.created),
            12.5f,
            ui.sub(),
            false);
    period.setPadding(0, dp(4), 0, dp(10));
    summary.addView(period);
    long percent = r.indexed == 0 ? 0 : (100L * r.analyzed / r.indexed);
    summary.addView(
        ui.labeledMeter(
            t("coverage") + ": " + r.analyzed + " / " + r.indexed,
            percent + "%",
            percent / 100f,
            r.complete ? ui.good() : ui.warn()));
    LinearLayout badges = new LinearLayout(getParentActivity());
    badges.setPadding(0, dp(10), 0, 0);
    badges.addView(ui.pill(r.complete ? t("done") : t("partial"), r.complete ? ui.good() : ui.warn()));
    badges.addView(ui.pill(r.claims.size() + " " + t("evidence"), ui.accent()), margins(-2, -2, 6, 0, 0, 0));
    summary.addView(badges);
    TextView note = ui.text(t("coverageIndexed"), 12, ui.sub(), false);
    note.setPadding(0, dp(8), 0, 0);
    summary.addView(note);
    body.addView(summary, ui.cardParams());
    android.widget.HorizontalScrollView exports = ui.chipRow();
    ui.addChip(exports, ui.chip(t("export"), true, v -> exportReport(false, false)));
    ui.addChip(exports, ui.chip(t("preview"), false, v -> exportReport(false, true)));
    ui.addChip(exports, ui.chip(t("saveCloud"), false, v -> exportReport(true, false)));
    ui.addChip(exports, ui.chip(t("blocks"), false, v -> reportBuilder()));
    body.addView(exports, margins(-1, -2, 8, 0, 8, 4));
    CenterExport.Options options = exportOptions();
    for (String block : options.blocks) {
      if (block.equals("main")) renderMain(r);
      else if (block.equals("unclear")) {
        heading("unknown");
        for (String line : r.unclear) info(CenterReports.clean(line));
      } else if (block.equals("authors") && r.statistics != null) {
        heading("authors");
        info(t("authorWarning"));
        JSONArray authors = r.statistics.optJSONArray("authors");
        int topCount = 1;
        for (int i = 0; authors != null && i < authors.length(); i++)
          topCount = Math.max(topCount, authors.optJSONObject(i).optInt("count"));
        for (int i = 0; authors != null && i < Math.min(40, authors.length()); i++) {
          JSONObject author = authors.optJSONObject(i);
          LinearLayout meterBox = new LinearLayout(getParentActivity());
          meterBox.setOrientation(LinearLayout.VERTICAL);
          meterBox.addView(
              ui.labeledMeter(
                  (i + 1) + ". " + author.optString("name"),
                  "" + author.optInt("count"),
                  author.optInt("count") / (float) topCount,
                  ui.series(i)));
          meterBox.setBackground(ui.ripple(ui.shape(ui.card(), 14, ui.line(), 1), 14));
          meterBox.setPadding(dp(14), dp(8), dp(14), dp(8));
          meterBox.setOnClickListener(
              w -> {
                List<CenterData.Message> hits = new ArrayList<>();
                for (CenterData.Message m : index.selected())
                  if (m.author == author.optLong("id")) hits.add(m);
                messageDetails(hits);
              });
          body.addView(meterBox, margins(-1, -2, 12, 3, 12, 3));
        }
      } else if (block.equals("charts") && r.statistics != null) {
        heading("activity");
        int version = uiVersion;
        for (int mode : CenterExport.chartModes(r.statistics)) {
          final int kind = mode;
          LinearLayout chartCard = ui.card(8, 10);
          TextView chartTitle =
              ui.text(
                  t(new String[] {"rank", "days", "heatmap", "content", "concentration"}[mode]),
                  14,
                  ui.text(),
                  true);
          chartTitle.setPadding(dp(8), 0, dp(8), dp(6));
          chartCard.addView(chartTitle);
          ImageView chart = new ImageView(getParentActivity());
          chart.setScaleType(ImageView.ScaleType.FIT_CENTER);
          chartCard.addView(chart, new LinearLayout.LayoutParams(-1, dp(240)));
          body.addView(chartCard, ui.cardParams());
          TPAiClient.async(
              () -> {
                byte[] bytes =
                    CenterRaster.chart(
                        r.statistics,
                        kind,
                        new CenterExport.Names(options, TelePremiumAnalyzerActivity::t),
                        TelePremiumAnalyzerActivity::t);
                try {
                  return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                } finally {
                  Arrays.fill(bytes, (byte) 0);
                }
              },
              (bitmap, error) -> {
                if (version == uiVersion && foreground && unlocked) chart.setImageBitmap(bitmap);
              });
          chart.setOnClickListener(v -> show(2));
        }
        JSONArray groups = r.statistics.optJSONArray("groups");
        for (int i = 0; groups != null && i < groups.length(); i++) {
          JSONObject group = groups.optJSONObject(i);
          info(
              group.optString("title")
                  + " · "
                  + t("messages")
                  + ": "
                  + group.optInt("messages")
                  + " · "
                  + t("authors")
                  + ": "
                  + group.optInt("authors"));
        }
      } else if (block.equals("media") && options.images) {
        heading("media");
        Set<String> seen = new HashSet<>();
        int count = 0;
        for (CenterReports.Claim claim : r.claims)
          for (CenterReports.Evidence e : claim.evidence)
            if (count < 40 && seen.add(e.message.qualified()) && !e.message.kind.equals("text")) {
              reportImage(r, e, body);
              count++;
            }
      } else if (block.equals("transcripts")) {
        heading("transcripts");
        for (CenterData.Message transcript : CenterReports.transcripts(r))
          row(
              transcript.sourceTitle + " · " + transcript.accountName,
              () -> mediaDetails(transcript, 0));
      }
    }
  }

  private void reportImage(
      CenterReports.Report r, CenterReports.Evidence evidence, LinearLayout container) {
    CenterData.Message m = evidence.message;
    if (m.protectedMedia) return;
    LinearLayout frame = ui.card(0, 0);
    frame.setPadding(0, 0, 0, dp(10));
    ImageView image = new ImageView(getParentActivity());
    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
    image.setClipToOutline(true);
    image.setOutlineProvider(
        new ViewOutlineProvider() {
          @Override
          public void getOutline(View view, Outline outline) {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(14));
          }
        });
    frame.addView(image, new LinearLayout.LayoutParams(-1, dp(180)));
    container.addView(frame, ui.cardParams());
    int version = uiVersion;
    TPAiClient.async(
        () -> {
          byte[] bytes = CenterAssets.image(r, evidence);
          if (bytes == null) return null;
          try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 2;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
          } finally {
            Arrays.fill(bytes, (byte) 0);
          }
        },
        (bitmap, error) -> {
          if (version == uiVersion && foreground && unlocked) {
            if (bitmap != null) image.setImageBitmap(bitmap);
            else image.setVisibility(View.GONE);
          }
        });
    image.setOnClickListener(
        v -> {
          if (evidence.timeMs > 0) playAt(m, evidence.timeMs);
          else mediaDetails(m, evidence.page);
        });
    TextView caption =
        ui.text(
            m.authorName + " · " + m.sourceTitle + " · " + dateTime(m.date * 1000L)
                + (m.description.isEmpty() ? "" : "\n" + m.description),
            12.5f,
            ui.sub(),
            false);
    caption.setPadding(dp(14), dp(8), dp(14), 0);
    frame.addView(caption);
  }

  private void renderMain(CenterReports.Report r) {
    int firstClaim = Math.min(reportPage * 50, r.claims.size());
    for (CenterReports.Claim claim :
        r.claims.subList(firstClaim, Math.min((reportPage + 1) * 50, r.claims.size()))) {
      final int number = r.claims.indexOf(claim) + 1;
      LinearLayout card = ui.card(16, 14);
      LinearLayout head = new LinearLayout(getParentActivity());
      head.setGravity(Gravity.CENTER_VERTICAL);
      TextView num = ui.text("" + number, 13, ui.onAccent(), true);
      num.setGravity(Gravity.CENTER);
      num.setBackground(ui.shape(ui.accent(), 11, 0, 0));
      LinearLayout.LayoutParams numParams = new LinearLayout.LayoutParams(dp(22), dp(22));
      numParams.rightMargin = dp(10);
      head.addView(num, numParams);
      head.addView(ui.text(claim.title, 16, ui.text(), true), new LinearLayout.LayoutParams(0, -2, 1));
      card.addView(head);
      LinearLayout meta = new LinearLayout(getParentActivity());
      meta.setPadding(0, dp(8), 0, dp(6));
      boolean verified = claim.verified();
      meta.addView(
          ui.pill(
              t(verified ? claim.certainty : "unverified"),
              verified ? ui.good() : ui.warn()));
      if (!claim.topic.isEmpty())
        meta.addView(ui.pill(claim.topic, ui.accent()), margins(-2, -2, 6, 0, 0, 0));
      card.addView(meta);
      TextView textView = ui.text(claim.text, 14.5f, ui.text(), false);
      textView.setLineSpacing(dp(2), 1f);
      card.addView(textView);
      final LinearLayout evidence = new LinearLayout(getParentActivity());
      evidence.setOrientation(LinearLayout.VERTICAL);
      String evidenceKey = r.id + ":" + r.claims.indexOf(claim);
      Runnable toggleEvidence =
          () -> {
            boolean opening = evidence.getChildCount() == 0;

            if (evidence.getChildCount() > 0) evidence.removeAllViews();
            else
              for (CenterReports.Evidence ev : claim.evidence) {
                CenterData.Message m = ev.message;
                LinearLayout item = new LinearLayout(getParentActivity());
                item.setOrientation(LinearLayout.VERTICAL);
                item.setPadding(dp(12), dp(10), dp(12), dp(10));
                item.setBackground(
                    ui.ripple(ui.shape(TelePremiumCenterUi.alpha(ui.accent(), 14), 12, 0, 0), 12));
                LinearLayout identity = new LinearLayout(getParentActivity());
                identity.setGravity(Gravity.CENTER_VERTICAL);
                avatar(identity, m.account, m.author > 0 ? m.author : m.dialog);
                LinearLayout who = new LinearLayout(getParentActivity());
                who.setOrientation(LinearLayout.VERTICAL);
                TextView author = ui.text(m.authorName, 14, ui.text(), true);
                author.setSingleLine(true);
                author.setEllipsize(android.text.TextUtils.TruncateAt.END);
                who.addView(author);
                TextView where =
                    ui.text(
                        m.sourceTitle + " · " + m.accountName + " · " + dateTime(m.date * 1000L),
                        11.5f,
                        ui.sub(),
                        false);
                where.setSingleLine(true);
                where.setEllipsize(android.text.TextUtils.TruncateAt.END);
                who.addView(where);
                identity.addView(who, new LinearLayout.LayoutParams(0, -2, 1));
                item.addView(identity);
                TextView quote = ui.text("“" + ev.quote + "”", 14, ui.text(), false);
                quote.setPadding(dp(4), dp(8), dp(4), 0);
                quote.setTypeface(Typeface.defaultFromStyle(Typeface.ITALIC));
                item.addView(quote);
                if (m.deleted) item.addView(ui.pill(t("deleted"), ui.bad()), margins(-2, -2, 0, 6, 0, 0));
                LinearLayout chips = new LinearLayout(getParentActivity());
                chips.setPadding(0, dp(8), 0, 0);
                chips.addView(
                    ui.chip(tr("Open message", "Открыть сообщение"), true, w -> openEvidence(m)));
                if (ev.timeMs > 0)
                  chips.addView(
                      ui.chip(CenterTranscript.stamp(ev.timeMs), false, w -> playAt(m, ev.timeMs)),
                      margins(-2, -2, 6, 0, 0, 0));
                if (ev.page > 0)
                  chips.addView(
                      ui.chip(t("page") + " " + ev.page, false, w -> mediaDetails(m, ev.page)),
                      margins(-2, -2, 6, 0, 0, 0));
                chips.addView(
                    ui.chip("⋯", false, w -> evidenceActions(m, claim)),
                    margins(-2, -2, 6, 0, 0, 0));
                item.addView(chips);
                item.setOnClickListener(w -> openEvidence(m));
                item.setOnLongClickListener(
                    w -> {
                      evidenceActions(m, claim);
                      return true;
                    });
                evidence.addView(item, margins(-1, -2, 0, 6, 0, 0));
                if (exportOptions().images) reportImage(r, ev, evidence);
              }

            if (opening) expandedEvidence.add(evidenceKey);
            else expandedEvidence.remove(evidenceKey);
          };
      LinearLayout actions = new LinearLayout(getParentActivity());
      actions.setPadding(0, dp(12), 0, 0);
      actions.addView(
          ui.chip(t("evidence") + " (" + claim.evidence.size() + ")", false, v -> toggleEvidence.run()));
      actions.addView(
          ui.chip(
              t("expand"),
              false,
              v -> {
                drilldown = new ArrayList<>();
                for (CenterReports.Evidence ev : claim.evidence) drilldown.add(ev.message);
                query = t("expand") + ": " + claim.title;
                draft = query;
                show(6);
              }),
          margins(-2, -2, 8, 0, 0, 0));
      card.addView(actions);
      card.addView(evidence);
      body.addView(card, ui.cardParams());
      if (expandedEvidence.contains(evidenceKey)) toggleEvidence.run();
    }
    if (reportPage > 0)
      button(
          "previous",
          () -> {
            reportPage--;
            show(screen);
          });
    if ((reportPage + 1) * 50 < r.claims.size())
      button(
          "next",
          () -> {
            reportPage++;
            show(screen);
          });
  }

  private void evidenceActions(CenterData.Message m, CenterReports.Claim claim) {
    choices(
        "evidence",
        new String[] {t("context"), t("link"), t("authors")},
        which -> {
          if (which == 0) {
            LinearLayout lines = new LinearLayout(getParentActivity());
            lines.setOrientation(1);
            renderContext(lines, CenterReports.context(index.messages, m));
            int version = uiVersion;
            long epoch = CenterIndex.generation();
            if (m.dialog != 0 && !m.deleted)
              TPAiClient.async(
                  () -> CenterSync.context(m, new java.util.concurrent.atomic.AtomicBoolean()),
                  (rows, error) -> {
                    if (error == null
                        && unlocked
                        && foreground
                        && version == uiVersion
                        && epoch == CenterIndex.generation()) renderContext(lines, rows);
                  });
            ScrollView sv = new ScrollView(getParentActivity());
            sv.addView(lines);
            showDialog(
                new org.telegram.ui.ActionBar.BottomSheet.Builder(getParentActivity())
                    .setTitle(t("context"))
                    .setCustomView(sv)
                    .create());
          } else if (which == 1) {
            String link =
                m.source.startsWith("c:")
                    ? "https://t.me/c/" + (-m.dialog) + "/" + m.id
                    : "tg://openmessage?"
                        + (m.dialog > 0 ? "user_id=" + m.dialog : "chat_id=" + (-m.dialog))
                        + "&message_id="
                        + m.id;
            ((ClipboardManager) getParentActivity().getSystemService(Context.CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText(m.sourceTitle, link));
            toast(t("done"));
          } else {
            Set<String> authors = new LinkedHashSet<>();
            for (CenterReports.Evidence ev : claim.evidence) authors.add(ev.message.authorName);
            dialogBuilder()
                .setTitle(t("authors"))
                .setMessage(android.text.TextUtils.join("\n", authors))
                .setPositiveButton(t("done"), null)
                .show();
          }
        });
  }

  private void renderContext(LinearLayout lines, List<CenterData.Message> rows) {
    lines.removeAllViews();
    for (CenterData.Message message : rows) {
      TextView text =
          label(
              message.authorName
                  + " · "
                  + new Date(message.date * 1000L)
                  + "\n"
                  + message.text
                  + (message.deleted ? "\n" + t("deleted") : ""),
              14);
      text.setOnClickListener(v -> openEvidence(message));
      lines.addView(text);
    }
  }

  private void openEvidence(CenterData.Message m) {
    if (m.dialog == 0) {
      if (Arrays.asList("video", "audio", "voice", "round").contains(m.kind)) playAt(m, 0);
      else mediaDetails(m, 0);
      return;
    }
    if (m.deleted) {
      dialogBuilder()
          .setTitle(t("deleted"))
          .setMessage(m.text)
          .setPositiveButton(t("done"), null)
          .show();
      return;
    }
    Runnable open =
        () -> {
          if (!CenterIndex.accountAllowed(m.account, m.owner)) {
            toast(t("excluded"));
            return;
          }
          if (UserConfig.selectedAccount != m.account
              && getParentActivity() instanceof LaunchActivity)
            ((LaunchActivity) getParentActivity()).switchToAccount(m.account, false);
          Bundle args = new Bundle();
          if (m.dialog < 0) args.putLong("chat_id", -m.dialog);
          else args.putLong("user_id", m.dialog);
          args.putInt("message_id", m.id);
          ChatActivity chat = new ChatActivity(args);
          chat.setCurrentAccount(m.account);
          presentFragment(chat);
        };
    if (UserConfig.selectedAccount == m.account) open.run();
    else
      dialogBuilder()
          .setMessage(t("switchAccount"))
          .setPositiveButton(t("confirm"), (d, w) -> open.run())
          .setNegativeButton(t("cancel"), null)
          .show();
  }

  private CenterExport.Options exportOptions() {
    return CenterExport.Options.parse(CenterIndex.settings().optJSONObject("exportOptions"));
  }

  private void persistReport(CenterReports.Report r, String kind) {
    CenterStore.IO.execute(
        () -> {
          try {
            CenterAssets.save(r, kind, exportOptions());
            AndroidUtilities.runOnUIThread(() -> toast(t("done")));
          } catch (Exception e) {
            AndroidUtilities.runOnUIThread(() -> error(e.getMessage()));
          }
        });
  }

  private void history() {
    listReports("thread");
  }

  private void reportArchive() {
    if (archiveDetail && report != null) {
      button(
          "reports",
          () -> {
            archiveDetail = false;
            listReports("report");
          });
      renderReport(report);
    } else listReports("report");
  }

  private void listReports(String kind) {
    status.setText(t("loading"));
    CenterStore.IO.execute(
        () -> {
          List<JSONObject> values = new ArrayList<>();
          try {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++)
              if (UserConfig.getInstance(a).isClientActivated()
                  && !index.scope.excluded.contains(a))
                values.addAll(
                    CenterStore.get().list(a, UserConfig.getInstance(a).getClientUserId(), kind));
          } catch (Exception e) {
            FileLog.e(e);
          }
          AndroidUtilities.runOnUIThread(
              () -> {
                if (!unlocked || !foreground) return;
                status.setText("");
                clearBody();
                body.addView(
                    ui.segmented(
                        new String[] {tr("Reports", "Отчёты"), tr("Analyst chats", "Диалоги аналитика")},
                        kind.equals("report") ? 0 : 1,
                        i -> listReports(i == 0 ? "report" : "thread")),
                    margins(-1, -2, 12, 10, 12, 4));
                if (values.isEmpty())
                  body.addView(
                      ui.empty(
                          R.drawable.tp_aic_reports,
                          tr("Nothing saved yet", "Пока ничего не сохранено"),
                          tr(
                              "Collect a summary or ask the analyst, then tap “Save” under the result.",
                              "Соберите сводку или задайте вопрос аналитику, затем нажмите «В архив» под результатом.")));
                for (JSONObject o : values) {
                  CenterReports.Report r = CenterReports.restore(o, index.readable());
                  LinearLayout item =
                      ui.row(
                          R.drawable.tp_aic_reports,
                          r.question,
                          dateTime(r.created) + " · " + r.claims.size() + " " + t("evidence"),
                          ui.icon(R.drawable.tp_aic_chevron, ui.sub(), 20),
                          w -> {
                            report = r;
                            query = r.question;
                            reportPage = 0;
                            if (kind.equals("report")) {
                              archiveDetail = true;
                              show(7);
                            } else {
                              analystReport = r;
                              show(6);
                            }
                          });
                  body.addView(item, margins(-1, -2, 12, 3, 12, 3));
                }
                button(
                    "delete",
                    () -> {
                      CenterStore.IO.execute(
                          () -> {
                            try {
                              for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++)
                                if (UserConfig.getInstance(a).isClientActivated())
                                  for (JSONObject o :
                                      CenterStore.get()
                                          .list(
                                              a, UserConfig.getInstance(a).getClientUserId(), kind))
                                    CenterStore.get()
                                        .remove(
                                            a,
                                            UserConfig.getInstance(a).getClientUserId(),
                                            kind,
                                            o.optString("id"));
                            } catch (Exception e) {
                              FileLog.e(e);
                            }
                          });
                      report = null;
                      show(screen);
                    });
              });
        });
  }

  private boolean kindMatches(String filter, String kind) {
    switch (filter) {
      case "":
        return true;
      case "photo":
        return kind.equals("photo") || kind.equals("image");
      case "video":
        return kind.equals("video") || kind.equals("round");
      case "docs":
        return kind.equals("pdf") || kind.equals("document");
      case "audio":
        return kind.equals("audio") || kind.equals("voice");
      default:
        return filter.equals(kind);
    }
  }

  private int kindIcon(String kind) {
    switch (kind) {
      case "video":
      case "round":
        return R.drawable.tp_aic_play;
      case "audio":
      case "voice":
        return R.drawable.tp_aic_transcriber;
      case "pdf":
      case "document":
        return R.drawable.tp_aic_reports;
      default:
        return R.drawable.tp_aic_media;
    }
  }

  private String kindName(String kind) {
    switch (kind) {
      case "video":
        return tr("Video", "Видео");
      case "round":
        return tr("Video message", "Видеосообщение");
      case "audio":
        return tr("Audio", "Аудио");
      case "voice":
        return tr("Voice message", "Голосовое");
      case "pdf":
        return "PDF";
      case "document":
        return tr("Document", "Документ");
      case "photo":
      case "image":
        return tr("Photo", "Фото");
      default:
        return t(kind.equals("link") ? "links" : kind);
    }
  }

  private String clock(long millis) {
    long seconds = Math.max(0, millis / 1000);
    return seconds >= 3600
        ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
  }

  private List<CenterData.Message> mediaMessages(boolean speech) {
    List<CenterData.Message> out = new ArrayList<>();
    for (CenterData.Message m : index.selected())
      if (!m.protectedMedia
          && !m.kind.equals("text")
          && !m.kind.equals("link")
          && (!speech || Arrays.asList("video", "audio", "voice", "round").contains(m.kind))
          && kindMatches(mediaKind, m.kind)
          && (mediaCategory.isEmpty() || mediaCategory.equals(CenterTopics.category(m)))
          && (semanticHits == null || semanticHits.contains(m.qualified()))
          && (!speech
              || transcriberFilter == 2
              || (transcriberFilter == 0) == m.transcript.isEmpty())
          && m.searchable()
              .toLowerCase(Locale.ROOT)
              .contains(mediaSearch.toLowerCase(Locale.ROOT))) {
        try {
          if (CenterMedia.readableDerivative(m)) out.add(m);
        } catch (Exception ignored) {
        }
      }
    return out;
  }

  private int transcriberFilter;

  private void semanticSearch(String question) {
    semanticHits = null;
    String previous = mediaSearch;
    mediaSearch = "";
    List<CenterData.Message> data = mediaMessages(false);
    mediaSearch = previous;
    dialogBuilder()
        .setMessage(
            t("consent") + "\n" + t("estimate") + ": " + data.size() + " · " + t("indexedDescriptions"))
        .setPositiveButton(
            t("confirm"),
            (d, w) -> {
              CenterIndex.setting("consent", true);
              aiCancelled.set(false);
              status.setText(t("loading"));
              TPAiClient.async(
                  () -> CenterSearch.find(data, question, aiCancelled),
                  (hits, error) -> {
                    if (!foreground || !unlocked || aiCancelled.get()) return;
                    status.setText("");
                    if (error != null) error(error);
                    else {
                      semanticHits = hits;
                      mediaSearch = "";
                      mediaPage = 0;
                      show(screen);
                    }
                  });
            })
        .setNegativeButton(t("cancel"), null)
        .show();
  }

  private EditText mediaSearchField(String hint) {
    final EditText search = ui.field(hint, mediaSearch, 1);
    search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
    search.setOnEditorActionListener(
        (v, action, event) -> {
          mediaSearch = v.getText().toString().trim();
          semanticHits = null;
          mediaPage = 0;
          AndroidUtilities.hideKeyboard(v);
          show(screen);
          return true;
        });
    return search;
  }

  private void addFileChips(android.widget.HorizontalScrollView row) {
    ui.addChip(row, ui.chip("+ " + tr("File", "Файл"), false, v -> pickFile()));
    ui.addChip(row, ui.chip("+ " + tr("From cloud", "Из облака"), false, v -> pickCloud()));
  }

  private void pickFile() {
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    i.addCategory(Intent.CATEGORY_OPENABLE);
    i.setType("*/*");
    i.putExtra(
        Intent.EXTRA_MIME_TYPES,
        new String[] {
          "audio/*",
          "video/*",
          "image/*",
          "application/pdf",
          "text/*",
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
          "application/vnd.oasis.opendocument.text"
        });
    i.addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    startActivityForResult(i, 72581);
  }

  private void selectionMark(View mark, boolean on) {
    mark.setBackground(
        on ? ui.shape(ui.accent(), 13, 0, 0) : ui.shape(TelePremiumCenterUi.alpha(0xff000000, 90), 13, 0xffffffff, 1.5f));
    if (mark instanceof FrameLayout) {
      FrameLayout f = (FrameLayout) mark;
      f.getChildAt(0).setVisibility(on ? View.VISIBLE : View.INVISIBLE);
    }
  }

  /** "Медиа": a library of pictures, video and documents with search, filters and AI actions. */
  private void mediaLibrary() {
    LinearLayout box = ui.card(12, 10);
    LinearLayout line = new LinearLayout(getParentActivity());
    line.setGravity(Gravity.CENTER_VERTICAL);
    final EditText search =
        mediaSearchField(tr("Search by text, caption or description", "Поиск по тексту, подписи, описанию"));
    line.addView(search, new LinearLayout.LayoutParams(0, -2, 1));
    line.addView(
        ui.chip(
            "✨ " + tr("By meaning", "По смыслу"),
            semanticHits != null,
            v -> {
              String question = search.getText().toString().trim();
              if (question.isEmpty()) {
                toast(
                    tr(
                        "Type what you are looking for, e.g. “receipt for the laptop”.",
                        "Введите, что ищете, например «чек на ноутбук»."));
                return;
              }
              semanticSearch(question);
            }),
        margins(-2, -2, 8, 0, 0, 0));
    box.addView(line);
    String[] kinds = {"", "photo", "video", "docs", "audio"};
    String[] names = {
      tr("All", "Все"),
      tr("Photos", "Фото"),
      tr("Video", "Видео"),
      tr("Documents", "Документы"),
      tr("Audio", "Аудио")
    };
    android.widget.HorizontalScrollView kindRow = ui.chipRow();
    kindRow.getChildAt(0).setPadding(0, dp(10), 0, dp(2));
    for (int i = 0; i < kinds.length; i++) {
      final String value = kinds[i];
      ui.addChip(
          kindRow,
          ui.chip(
              names[i],
              mediaKind.equals(value),
              v -> {
                mediaKind = value;
                mediaPage = 0;
                show(screen);
              }));
    }
    box.addView(kindRow);
    if (mediaKind.isEmpty() || mediaKind.equals("photo")) {
      TextView what =
          ui.text(
              tr("What is in the picture (found by AI after “Describe”)", "Что на изображении (определяет ИИ после «Описать»)"),
              12,
              ui.sub(),
              false);
      what.setPadding(dp(2), dp(8), 0, 0);
      box.addView(what);
      String[] values = {"", "chartImage", "receiptImage", "chatImage", "memeImage", "productImage", "peopleImage", "photoImage"};
      String[] labels = {
        tr("Any", "Любое"),
        tr("Charts", "Графики"),
        tr("Receipts", "Чеки"),
        tr("Chats", "Переписки"),
        tr("Memes", "Мемы"),
        tr("Products", "Товары"),
        tr("People", "Люди"),
        tr("Other photos", "Прочие фото")
      };
      android.widget.HorizontalScrollView categoryRow = ui.chipRow();
      categoryRow.getChildAt(0).setPadding(0, dp(4), 0, 0);
      for (int i = 0; i < values.length; i++) {
        final String value = values[i];
        ui.addChip(
            categoryRow,
            ui.chip(
                labels[i],
                mediaCategory.equals(value),
                v -> {
                  mediaCategory = value;
                  mediaPage = 0;
                  show(screen);
                }));
      }
      box.addView(categoryRow);
    }
    body.addView(box, ui.cardParams());

    final List<CenterData.Message> list = mediaMessages(false);
    android.widget.HorizontalScrollView tools = ui.chipRow();
    ui.addChip(
        tools,
        ui.chip(
            tr("Select all", "Выбрать все") + " (" + list.size() + ")",
            false,
            v -> {
              for (CenterData.Message m : list) pickedMedia.add(m.qualified());
              show(screen);
            }));
    ui.addChip(tools, ui.chip(tr("Task queue", "Очередь задач"), false, v -> taskQueue()));
    addFileChips(tools);
    body.addView(tools, margins(-1, -2, 4, 2, 4, 0));
    if (list.isEmpty()) {
      body.addView(
          ui.empty(
              R.drawable.tp_aic_media,
              tr("No media here", "Здесь нет медиа"),
              tr(
                  "Pictures, video and documents appear after you load the groups on the Sources screen. Only items already downloaded to the Center are shown.",
                  "Изображения, видео и документы появляются после загрузки групп на экране «Источники». Показаны только те, что уже сохранены в центре.")));
    } else if (pickedMedia.isEmpty()) {
      TextView hint =
          ui.text(
              tr(
                  "Tick the items you need — actions appear at the bottom: Download (save to the phone), Read text (find the writing on pictures), Describe (AI tells what is shown).",
                  "Отметьте нужные галочкой — внизу появятся действия: «Скачать» (сохранить на телефон), «Читать текст» (найти надписи на картинках), «Описать» (ИИ расскажет, что изображено)."),
              12.5f,
              ui.sub(),
              false);
      hint.setPadding(dp(18), dp(8), dp(18), dp(4));
      body.addView(hint);
    }
    int version = uiVersion;
    LinearLayout galleryRow = null;
    for (int i = mediaPage * 40; i < Math.min(list.size(), (mediaPage + 1) * 40); i++) {
      final CenterData.Message m = list.get(i);
      boolean grid = Arrays.asList("photo", "image", "video", "round").contains(m.kind);
      LinearLayout card = ui.card(0, 0);
      card.setPadding(0, 0, 0, dp(10));
      final boolean picked = pickedMedia.contains(m.qualified());
      if (picked) card.setBackground(ui.ripple(ui.shape(TelePremiumCenterUi.alpha(ui.accent(), 22), 14, ui.accent(), 2), 14));
      if (grid) {
        if (galleryRow == null || galleryRow.getChildCount() == 2) {
          galleryRow = new LinearLayout(getParentActivity());
          body.addView(galleryRow, margins(-1, -2, 8, 4, 8, 0));
        }
        galleryRow.addView(card, margins(0, -2, 4, 0, 4, 0));
        ((LinearLayout.LayoutParams) card.getLayoutParams()).weight = 1;
        ((LinearLayout.LayoutParams) card.getLayoutParams()).width = 0;
      } else {
        galleryRow = null;
        body.addView(card, ui.cardParams());
      }
      FrameLayout frame = new FrameLayout(getParentActivity());
      final ImageView preview = new ImageView(getParentActivity());
      preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
      preview.setBackgroundColor(TelePremiumCenterUi.alpha(ui.sub(), 30));
      preview.setClipToOutline(true);
      preview.setOutlineProvider(
          new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
              outline.setRoundRect(0, 0, view.getWidth(), view.getHeight() + dp(14), dp(14));
            }
          });
      boolean hasPreview = !m.frameTimes.isEmpty() || m.pages > 0;
      frame.addView(preview, new FrameLayout.LayoutParams(-1, dp(grid ? 130 : 72)));
      if (!hasPreview) {
        ImageView symbol = ui.icon(kindIcon(m.kind), ui.sub(), 30);
        frame.addView(symbol, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
      } else {
        CenterThumbnails.load(
            m,
            m.frameTimes.isEmpty() ? 0 : m.frameTimes.get(0),
            m.pages > 0 ? 1 : 0,
            b -> {
              if (version == uiVersion && b != null) preview.setImageBitmap(b);
            });
      }
      final FrameLayout mark = new FrameLayout(getParentActivity());
      final ImageView tick = ui.icon(R.drawable.tp_aic_check, 0xffffffff, 16);
      mark.addView(tick, new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
      selectionMark(mark, picked);
      FrameLayout.LayoutParams markParams = new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP | Gravity.END);
      markParams.setMargins(0, dp(8), dp(8), 0);
      frame.addView(mark, markParams);
      Runnable toggle =
          () -> {
            if (!pickedMedia.remove(m.qualified())) pickedMedia.add(m.qualified());
            show(screen);
          };
      mark.setOnClickListener(v -> toggle.run());
      card.addView(frame, new LinearLayout.LayoutParams(-1, -2));
      LinearLayout meta = new LinearLayout(getParentActivity());
      meta.setOrientation(LinearLayout.VERTICAL);
      meta.setPadding(dp(10), dp(8), dp(10), 0);
      TextView who = ui.text(m.authorName, 13.5f, ui.text(), true);
      who.setSingleLine(true);
      who.setEllipsize(android.text.TextUtils.TruncateAt.END);
      meta.addView(who);
      TextView where =
          ui.text(m.sourceTitle + " · " + dateOnly(m.date), 11.5f, ui.sub(), false);
      where.setSingleLine(true);
      where.setEllipsize(android.text.TextUtils.TruncateAt.END);
      meta.addView(where);
      LinearLayout pills = new LinearLayout(getParentActivity());
      pills.setPadding(0, dp(6), 0, 0);
      pills.addView(ui.pill(kindName(m.kind), ui.accent()));
      if (!m.ocr.isEmpty())
        pills.addView(ui.pill(tr("Text", "Текст"), ui.good()), margins(-2, -2, 4, 0, 0, 0));
      if (!m.description.isEmpty())
        pills.addView(ui.pill(tr("Described", "Описано"), ui.good()), margins(-2, -2, 4, 0, 0, 0));
      if (!m.transcript.isEmpty())
        pills.addView(ui.pill(tr("Transcript", "Расшифровка"), ui.good()), margins(-2, -2, 4, 0, 0, 0));
      meta.addView(pills);
      if (!grid && !m.text.isEmpty()) {
        TextView caption = ui.text(m.text.length() > 140 ? m.text.substring(0, 140) + "…" : m.text, 12.5f, ui.sub(), false);
        caption.setPadding(0, dp(6), 0, 0);
        meta.addView(caption);
      }
      if (!m.hash.isEmpty())
        try {
          long hash = Long.parseUnsignedLong(m.hash, 16);
          List<CenterData.Message> copies = new ArrayList<>();
          for (CenterData.Message other : index.selected())
            if (!other.hash.isEmpty()
                && Long.bitCount(hash ^ Long.parseUnsignedLong(other.hash, 16)) <= 6)
              copies.add(other);
          copies.sort(
              Comparator.comparingInt((CenterData.Message x) -> x.date)
                  .thenComparingInt(x -> x.id));
          if (copies.size() > 1) {
            TextView duplicates =
                ui.text(
                    tr("Seen in ", "Встречается в ") + copies.size() + " " + tr("places · first: ", "местах · первым: ") + copies.get(0).authorName,
                    11.5f,
                    ui.warn(),
                    true);
            duplicates.setPadding(0, dp(6), 0, 0);
            duplicates.setOnClickListener(v -> messageDetails(copies));
            meta.addView(duplicates);
          }
        } catch (NumberFormatException invalid) {
        }
      card.addView(meta);
      card.setOnClickListener(v -> mediaDetails(m, 0));
      card.setOnLongClickListener(
          v -> {
            toggle.run();
            return true;
          });
    }
    if (mediaPage > 0)
      button(
          "previous",
          () -> {
            mediaPage--;
            show(screen);
          });
    if ((mediaPage + 1) * 40 < list.size())
      button(
          "next",
          () -> {
            mediaPage++;
            show(screen);
          });
    if (!pickedMedia.isEmpty()) {
      dock.setVisibility(View.VISIBLE);
      View separator = new View(getParentActivity());
      separator.setBackgroundColor(ui.line());
      dock.addView(separator, new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f))));
      LinearLayout head = new LinearLayout(getParentActivity());
      head.setGravity(Gravity.CENTER_VERTICAL);
      head.setPadding(dp(16), dp(8), dp(10), 0);
      head.addView(
          ui.text(tr("Selected", "Выбрано") + ": " + pickedMedia.size(), 14, ui.text(), true),
          new LinearLayout.LayoutParams(0, -2, 1));
      head.addView(
          ui.chip(
              tr("Clear", "Снять"),
              false,
              v -> {
                pickedMedia.clear();
                show(screen);
              }));
      dock.addView(head);
      dock.addView(
          ui.buttonRow(
              ui.button(tr("Download", "Скачать"), false, R.drawable.tp_aic_download, v -> queueMedia(list, "download")),
              ui.button(tr("Read text", "Читать текст"), false, 0, v -> queueMedia(list, "ocr")),
              ui.button(tr("Describe", "Описать"), true, 0, v -> queueMedia(list, "describe"))));
    }
  }

  private void chooseLanguage() {
    String[] codes = {"auto", "ru", "en", "uk", "de", "es", "fr", "tr", "ar", "zh"};
    String[] names = {
      tr("Detect automatically", "Определять автоматически"),
      "Русский",
      "English",
      "Українська",
      "Deutsch",
      "Español",
      "Français",
      "Türkçe",
      "العربية",
      "中文",
      tr("Other code…", "Другой код…")
    };
    choices(
        "language",
        names,
        i -> {
          if (i < codes.length) {
            CenterIndex.setting("language", codes[i]);
            show(screen);
          } else
            input(
                "language",
                CenterIndex.settings().optString("language", "auto"),
                v -> {
                  CenterIndex.setting("language", v);
                  show(screen);
                });
        });
  }

  private String languageName() {
    String code = CenterIndex.settings().optString("language", "auto");
    return code.equals("auto") ? tr("auto", "авто") : code;
  }

  /** "Транскрибатор": choose how, pick recordings, press the one button. */
  private void transcriber() {
    JSONObject settings = CenterIndex.settings();
    final boolean cloud = "cloud".equals(settings.optString("asrEngine", "device"));
    LinearLayout how = ui.card(14, 12);
    how.addView(ui.text(tr("1 · How to recognise", "1 · Как распознавать"), 15, ui.text(), true));
    LinearLayout engine =
        ui.segmented(
            new String[] {tr("On this phone", "На телефоне"), tr("In the cloud", "В облаке")},
            cloud ? 1 : 0,
            i -> {
              CenterIndex.setting("asrEngine", i == 1 ? "cloud" : "device");
              show(5);
            });
    how.addView(engine, margins(-1, -2, 0, 8, 0, 0));
    TextView explain =
        ui.text(
            cloud
                ? tr(
                    "Audio is sent to your speech-recognition model (" + settings.optString("asrModel", "whisper-1") + "). Needs internet and your confirmation.",
                    "Аудио отправляется вашей модели распознавания речи (" + settings.optString("asrModel", "whisper-1") + "). Нужен интернет и ваше подтверждение.")
                : tr(
                    "Recognised on the phone: the audio never leaves the device.",
                    "Распознаётся на телефоне: аудио не покидает устройство."),
            12.5f,
            ui.sub(),
            false);
    explain.setPadding(dp(2), dp(8), dp(2), dp(4));
    how.addView(explain);
    android.widget.HorizontalScrollView options = ui.chipRow();
    options.getChildAt(0).setPadding(0, dp(4), 0, dp(4));
    ui.addChip(options, ui.chip(tr("Language", "Язык") + ": " + languageName(), false, v -> chooseLanguage()));
    if (cloud)
      ui.addChip(options, ui.chip(tr("Model", "Модель") + ": " + settings.optString("asrModel", "whisper-1"), false, v -> chooseRole("speechModel", "cloudAsr")));
    how.addView(options);
    final Switch speakers =
        ui.switchView(settings.optBoolean("asrDiarization", false), on -> CenterIndex.setting("asrDiarization", on));
    how.addView(
        switchLine(
            tr("Separate speakers", "Разделять по спикерам"),
            t("speakerScopeHint"),
            speakers));
    body.addView(how, ui.cardParams());

    LinearLayout pick = ui.card(14, 12);
    pick.addView(ui.text(tr("2 · Choose recordings", "2 · Выберите записи"), 15, ui.text(), true));
    final EditText search = mediaSearchField(tr("Search recordings", "Поиск по записям"));
    pick.addView(search, margins(-1, -2, 0, 8, 0, 0));
    LinearLayout filter =
        ui.segmented(
            new String[] {tr("Waiting", "Ждут"), tr("Done", "Готово"), tr("All", "Все")},
            transcriberFilter,
            i -> {
              transcriberFilter = i;
              mediaPage = 0;
              show(5);
            });
    pick.addView(filter, margins(-1, -2, 0, 8, 0, 0));
    android.widget.HorizontalScrollView sourcesRow = ui.chipRow();
    sourcesRow.getChildAt(0).setPadding(0, dp(8), 0, 0);
    ui.addChip(sourcesRow, ui.chip("+ " + tr("My file", "Мой файл"), false, v -> pickFile()));
    ui.addChip(sourcesRow, ui.chip("+ " + tr("From cloud", "Из облака"), false, v -> pickCloud()));
    pick.addView(sourcesRow);
    body.addView(pick, ui.cardParams());

    final List<CenterData.Message> list = mediaMessages(true);
    if (list.isEmpty()) {
      body.addView(
          ui.empty(
              R.drawable.tp_aic_transcriber,
              transcriberFilter == 1 ? tr("Nothing recognised yet", "Пока ничего не расшифровано") : tr("No recordings", "Записей нет"),
              tr(
                  "Voice messages, audio and video from your groups appear here after loading. You can also add your own file.",
                  "Голосовые, аудио и видео из ваших групп появляются здесь после загрузки. Можно добавить и свой файл.")));
    }
    int version = uiVersion;
    for (int i = mediaPage * 40; i < Math.min(list.size(), (mediaPage + 1) * 40); i++) {
      final CenterData.Message m = list.get(i);
      final boolean picked = pickedMedia.contains(m.qualified());
      LinearLayout card = ui.clickableCard(12, 10, null);
      card.setBackground(
          ui.ripple(
              picked
                  ? ui.shape(TelePremiumCenterUi.alpha(ui.accent(), 22), 14, ui.accent(), 2)
                  : ui.shape(ui.card(), 14, ui.line(), 1),
              14));
      LinearLayout line = new LinearLayout(getParentActivity());
      line.setGravity(Gravity.CENTER_VERTICAL);
      line.addView(
          ui.badge(kindIcon(m.kind), ui.accent(), 40, 22),
          new LinearLayout.LayoutParams(dp(40), dp(40)));
      LinearLayout col = new LinearLayout(getParentActivity());
      col.setOrientation(LinearLayout.VERTICAL);
      col.setPadding(dp(12), 0, dp(8), 0);
      TextView who = ui.text(m.authorName + " · " + m.sourceTitle, 14.5f, ui.text(), true);
      who.setSingleLine(true);
      who.setEllipsize(android.text.TextUtils.TruncateAt.END);
      col.addView(who);
      col.addView(
          ui.text(
              kindName(m.kind) + " · " + (m.duration > 0 ? clock(m.duration) + " · " : "") + dateTime(m.date * 1000L),
              12,
              ui.sub(),
              false));
      line.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
      final FrameLayout mark = new FrameLayout(getParentActivity());
      mark.addView(ui.icon(R.drawable.tp_aic_check, 0xffffffff, 16), new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
      selectionMark(mark, picked);
      line.addView(mark, new LinearLayout.LayoutParams(dp(26), dp(26)));
      card.addView(line);
      if (!m.transcript.isEmpty()) {
        List<CenterTranscript.Segment> segments = CenterTranscript.parse(m.transcript, m.duration);
        for (int k = 0; k < Math.min(3, segments.size()); k++) {
          final CenterTranscript.Segment segment = segments.get(k);
          TextView text = ui.text(transcriptLine(segment), 13, ui.text(), false);
          text.setPadding(dp(4), dp(8), dp(4), 0);
          text.setOnClickListener(w -> playAt(m, segment.start));
          card.addView(text);
        }
        LinearLayout actions = new LinearLayout(getParentActivity());
        actions.setPadding(0, dp(8), 0, 0);
        actions.addView(
            ui.chip(
                tr("Open full transcript", "Открыть всю расшифровку") + " (" + segments.size() + ")",
                true,
                w -> mediaDetails(m, 0)));
        card.addView(actions);
      } else {
        card.addView(ui.pill(tr("Waiting for recognition", "Ждёт распознавания"), ui.warn()), margins(-2, -2, 0, 8, 0, 0));
      }
      card.setOnClickListener(
          v -> {
            if (!pickedMedia.remove(m.qualified())) pickedMedia.add(m.qualified());
            show(screen);
          });
      body.addView(card, ui.cardParams());
    }
    if (mediaPage > 0)
      button(
          "previous",
          () -> {
            mediaPage--;
            show(screen);
          });
    if ((mediaPage + 1) * 40 < list.size())
      button(
          "next",
          () -> {
            mediaPage++;
            show(screen);
          });

    dock.setVisibility(View.VISIBLE);
    View separator = new View(getParentActivity());
    separator.setBackgroundColor(ui.line());
    dock.addView(separator, new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f))));
    long minutes = 0;
    int count = 0;
    for (CenterData.Message m : list)
      if (pickedMedia.contains(m.qualified())) {
        count++;
        minutes += m.duration / 60000;
      }
    TextView summaryLine =
        ui.text(
            count == 0
                ? tr("3 · Nothing selected. Tap a recording to select it.", "3 · Ничего не выбрано. Нажмите на запись, чтобы выбрать.")
                : tr("3 · Selected", "3 · Выбрано") + ": " + count + " · ≈ " + Math.max(1, minutes) + " " + tr("min of audio", "мин записи"),
            13.5f,
            ui.text(),
            true);
    summaryLine.setPadding(dp(16), dp(8), dp(16), 0);
    dock.addView(summaryLine);
    TextView selectWaiting =
        ui.button(
            tr("Select all waiting", "Выбрать все ждущие"),
            false,
            0,
            v -> {
              for (CenterData.Message m : list) if (m.transcript.isEmpty()) pickedMedia.add(m.qualified());
              show(screen);
            });
    TextView queue = ui.button(tr("Queue", "Очередь"), false, 0, v -> taskQueue());
    TextView go =
        ui.button(
            tr("Recognise", "Распознать"),
            true,
            R.drawable.tp_aic_play,
            v -> {
              if (!cloud) {
                queueMedia(list, "transcribe");
                return;
              }
              dialogBuilder()
                  .setMessage(t("cloudAsrConsent"))
                  .setPositiveButton(
                      t("confirm"),
                      (d, w) -> {
                        CenterIndex.setting("consent", true);
                        queueMedia(list, "cloudAsr");
                      })
                  .setNegativeButton(t("cancel"), null)
                  .show();
            });
    dock.addView(ui.buttonRow(selectWaiting, queue, go));
  }

  private void enqueueDescriptions(List<CenterData.Message> selected, String question) {
    List<CenterData.Message> corpus = index.readable();
    List<CenterData.Source> sources = new ArrayList<>(index.sources);
    CenterData.Scope scope;
    try {
      scope = CenterData.Scope.parse(index.scope.json());
    } catch (Exception error) {
      error(error.getMessage());
      return;
    }
    long epoch = CenterIndex.generation();
    CenterStore.IO.execute(
        () -> {
          try {
            Map<String, JSONArray> context = new HashMap<>();
            for (CenterData.Message message : selected)
              context.put(
                  message.qualified(),
                  CenterMediaBundle.snapshot(
                      CenterMediaBundle.discussion(corpus, sources, scope, message)));
            if (epoch == CenterIndex.generation())
              CenterTaskService.enqueue(selected, "describe", question, context);
          } catch (Exception error) {
            AndroidUtilities.runOnUIThread(() -> error(error.getMessage()));
          }
        });
  }

  private String transcriptLine(CenterTranscript.Segment segment) {
    String speaker = CenterTranscript.speakerLabel(segment.speaker);
    return CenterTranscript.precise(segment.start)
        + (speaker.isEmpty() ? "" : " · " + t("speaker") + " " + speaker)
        + " · "
        + segment.text;
  }

  private void queueMedia(List<CenterData.Message> list, String op) {
    List<CenterData.Message> selected = new ArrayList<>();
    long bytes = 0, duration = 0;
    for (CenterData.Message m : list)
      if (pickedMedia.contains(m.qualified())) {
        selected.add(m);
        bytes += m.bytes;
        duration += m.duration;
      }
    if (selected.isEmpty()) {
      toast(t("selected"));
      return;
    }
    long totalBytes = bytes, totalDuration = duration;
    Runnable launch =
        () ->
            dialogBuilder()
                .setTitle(t("estimate"))
                .setMessage(
                    t("media")
                        + ": "
                        + selected.size()
                        + "\n"
                        + t("downloadEstimate")
                        + ": "
                        + totalBytes
                        + " / "
                        + (totalDuration / 60000)
                        + "\n"
                        + t("processingEstimate")
                        + ": "
                        + Math.max(1, totalDuration / 60000 + selected.size())
                        + (op.equals("describe") ? "\n" + t("discussionEstimate") : ""))
                .setPositiveButton(
                    t("continue"),
                    (d, w) -> {
                      if (op.equals("describe")) {
                        enqueueDescriptions(selected, "");
                        toast(t("queue"));
                        return;
                      }
                      for (CenterData.Message m : selected) {
                        String action = op;
                        if (op.equals("ocr") && (m.kind.equals("video") || m.kind.equals("round")))
                          action = "frames";
                        if ((op.equals("ocr") || op.equals("download"))
                            && (m.kind.equals("pdf") || m.kind.equals("document")))
                          action = "document";
                        if (op.equals("download")
                            && (m.kind.equals("video") || m.kind.equals("round")))
                          action = "frames";
                        CenterTaskService.enqueue(Collections.singletonList(m), action);
                      }
                      toast(t("queue"));
                    })
                .setNegativeButton(t("cancel"), null)
                .show();
    if (op.equals("describe") && !CenterIndex.settings().optBoolean("consent", false))
      dialogBuilder()
          .setMessage(t("consent"))
          .setPositiveButton(
              t("confirm"),
              (d, w) -> {
                CenterIndex.setting("consent", true);
                launch.run();
              })
          .setNegativeButton(t("cancel"), null)
          .show();
    else launch.run();
  }

  private void taskQueue() {
    int version = ++uiVersion;
    clearBody();
    heading("queue");
    CenterStore.IO.execute(
        () -> {
          List<JSONObject> jobs = new ArrayList<>();
          try {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++)
              if (UserConfig.getInstance(a).isClientActivated())
                jobs.addAll(
                    CenterStore.get().list(a, UserConfig.getInstance(a).getClientUserId(), "job"));
          } catch (Exception e) {
            FileLog.e(e);
          }
          AndroidUtilities.runOnUIThread(
              () -> {
                if (!unlocked || !foreground || version != uiVersion) return;
                for (JSONObject job : jobs) {
                  JSONObject m = job.optJSONObject("message");
                  info(
                      (m == null ? "" : m.optString("sourceTitle"))
                          + " · "
                          + t(job.optString("state"))
                          + " · "
                          + job.optInt("percent")
                          + "%\n"
                          + job.optString("error"));
                  button("pause", () -> CenterTaskService.change(job, "paused"));
                  button(
                      "resume",
                      () -> {
                        CenterTaskService.change(job, "queued");
                        CenterTaskService.startManual();
                      });
                  button("delete", () -> CenterTaskService.change(job, "cancelled"));
                }
                button("refresh", this::taskQueue);
                uiHandler.postDelayed(
                    () -> {
                      if (unlocked && foreground && version == uiVersion) taskQueue();
                    },
                    3000);
              });
        });
  }

  private void mediaDetails(CenterData.Message m, int page) {
    if (!detailKey.equals(m.qualified())) {
      detailKey = m.qualified();
      transcriptPage = 0;
    }
    LinearLayout panel = new LinearLayout(getParentActivity());
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setMinimumHeight(Math.max(dp(400), AndroidUtilities.displaySize.y - dp(120)));
    ZoomImage image = new ZoomImage(getParentActivity());
    panel.addView(
        image,
        new LinearLayout.LayoutParams(
            -1, Math.max(dp(240), (int) (AndroidUtilities.displaySize.y * .5f))));
    CenterThumbnails.loadFull(
        m,
        m.frameTimes.isEmpty() ? 0 : m.frameTimes.get(0),
        m.pages > 0 ? Math.max(1, page) : 0,
        b -> {
          if (b != null) image.setImageBitmap(b);
          else if (report != null) {
            CenterReports.Report archived = report;
            for (CenterReports.Claim claim : archived.claims)
              for (CenterReports.Evidence evidence : claim.evidence)
                if (evidence.message.qualified().equals(m.qualified())) {
                  TPAiClient.async(
                      () -> {
                        byte[] bytes = CenterAssets.image(archived, evidence);
                        if (bytes == null) return null;
                        try {
                          return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        } finally {
                          Arrays.fill(bytes, (byte) 0);
                        }
                      },
                      (bitmap, error) -> {
                        if (foreground
                            && unlocked
                            && CenterMedia.readableDerivative(m)
                            && bitmap != null) image.setImageBitmap(bitmap);
                      });
                  return;
                }
          }
        });
    ScrollView details = new ScrollView(getParentActivity());
    LinearLayout content = new LinearLayout(getParentActivity());
    content.setOrientation(1);
    details.addView(content);
    panel.addView(details, new LinearLayout.LayoutParams(-1, dp(250)));
    TextView metadata =
        label(
            m.authorName
                + " · "
                + m.sourceTitle
                + " · "
                + m.accountName
                + "\n"
                + new Date(m.date * 1000L)
                + "\n"
                + m.text
                + "\n"
                + m.description
                + "\n"
                + m.ocr,
            14);
    metadata.setTextIsSelectable(true);
    content.addView(metadata);
    if (!m.translation.isEmpty()) {
      content.addView(
          label(
              t("translation")
                  + " · "
                  + m.translationLanguage
                  + (m.translationComplete ? "" : " · " + t("partial"))
                  + "\n"
                  + m.translation,
              14));
      TextView translatedExport = label(t("exportTranslation"), 14);
      translatedExport.setOnClickListener(
          view ->
              choices(
                  "formats",
                  new String[] {"TXT", "SRT", "VTT"},
                  format -> {
                    String suffix = new String[] {"txt", "srt", "vtt"}[format];
                    TPAiClient.async(
                        () -> CenterFiles.transcript(m, suffix, true),
                        (file, failure) -> {
                          if (failure != null) error(failure);
                          else deliver(file, suffix, false, false);
                        });
                  }));
      content.addView(translatedExport);
    }

    List<CenterData.Message> discussion =
        CenterMediaBundle.discussion(index.readable(), index.sources, index.scope, m);
    if (!discussion.isEmpty()) {
      content.addView(label(t("discussionBlock"), 17));
      for (CenterData.Message message : discussion) {
        TextView line =
            label(
                message.authorName + " · " + new Date(message.date * 1000L) + "\n" + message.text,
                14);
        line.setOnClickListener(v -> openEvidence(message));
        content.addView(line);
      }
    }
    if (m.mediaAnalysis.optJSONArray("claims") != null) {
      CenterReports.Report analysis = CenterReports.restore(m.mediaAnalysis, index.readable());
      TextView proofs = label(t("evidence"), 15);
      content.addView(proofs);
      proofs.setOnClickListener(
          v -> {
            report = analysis;
            screen = 7;
            archiveDetail = true;
            show(7);
            if (mediaDialog != null) mediaDialog.dismiss();
          });
    }
    List<CenterTranscript.Segment> segments = CenterTranscript.parse(m.transcript, m.duration);
    for (int n = transcriptPage * 40;
        n < Math.min(segments.size(), (transcriptPage + 1) * 40);
        n++) {
      CenterTranscript.Segment segment = segments.get(n);
      TextView line = label(transcriptLine(segment), 14);
      content.addView(line);
      line.setOnClickListener(v -> playAt(m, segment.start));
    }
    AlertDialog dialog =
        dialogBuilder()
            .setView(panel)
            .setPositiveButton(t("open"), (d, w) -> openEvidence(m))
            .setNeutralButton(
                t("ask"),
                (d, w) ->
                    input(
                        "query",
                        "",
                        question -> {
                          Runnable launch =
                              () ->
                                  dialogBuilder()
                                      .setMessage(
                                          t("estimate")
                                              + ": "
                                              + m.bytes
                                              + " · "
                                              + t("media")
                                              + ": "
                                              + Math.max(1, m.frameTimes.size())
                                              + "\n"
                                              + t("discussionEstimate"))
                                      .setPositiveButton(
                                          t("continue"),
                                          (dialogInterface, which) -> {
                                            enqueueDescriptions(
                                                Collections.singletonList(m), question);
                                            toast(t("queue"));
                                          })
                                      .setNegativeButton(t("cancel"), null)
                                      .show();
                          if (CenterIndex.settings().optBoolean("consent", false)) launch.run();
                          else
                            dialogBuilder()
                                .setMessage(t("consent"))
                                .setPositiveButton(
                                    t("confirm"),
                                    (x, y) -> {
                                      CenterIndex.setting("consent", true);
                                      launch.run();
                                    })
                                .setNegativeButton(t("cancel"), null)
                                .show();
                        }))
            .setNegativeButton(t("cancel"), null)
            .create();
    if (transcriptPage > 0) {
      TextView previous = label(t("previous"), 14);
      content.addView(previous);
      previous.setOnClickListener(
          v -> {
            transcriptPage--;
            dialog.dismiss();
            mediaDetails(m, page);
          });
    }
    if ((transcriptPage + 1) * 40 < segments.size()) {
      TextView next = label(t("next"), 14);
      content.addView(next);
      next.setOnClickListener(
          v -> {
            transcriptPage++;
            dialog.dismiss();
            mediaDetails(m, page);
          });
    }
    if (m.pages > 1) {
      TextView next =
          label(t("page") + " " + Math.max(1, page) + " / " + m.pages + " · " + t("next"), 14);
      next.setOnClickListener(
          w -> {
            dialog.dismiss();
            mediaDetails(m, Math.max(1, page) % m.pages + 1);
          });
      content.addView(next);
    }
    TextView edit = label(t("edit"), 14);
    edit.setOnClickListener(
        w ->
            input(
                "edit",
                m.transcript,
                v -> {
                  m.transcript = v;
                  CenterMediaResults.invalidateTranslation(m);
                  CenterStore.IO.execute(
                      () -> {
                        try {
                          CenterMedia.checkIndexed(m);
                          CenterStore.get()
                              .put(m.account, m.owner, "message", m.qualified(), m.json());
                        } catch (Exception e) {
                          FileLog.e(e);
                        }
                      });
                }));
    content.addView(edit);
    TextView export = label(t("export"), 14);
    export.setOnClickListener(
        w ->
            choices(
                "formats",
                new String[] {"TXT", "SRT", "VTT"},
                i -> {
                  String text =
                      i == 0
                          ? m.transcript
                          : CenterTranscript.subtitles(
                              CenterTranscript.parse(m.transcript, m.duration), i == 2);
                  TPAiClient.async(
                      () -> CenterFiles.transcript(m, i == 0 ? "txt" : i == 1 ? "srt" : "vtt"),
                      (file, failure) -> {
                        if (failure != null) error(failure);
                        else deliver(file, i == 0 ? "txt" : i == 1 ? "srt" : "vtt", false, false);
                      });
                }));
    content.addView(export);
    TextView translate = label(t("translate"), 14);
    translate.setOnClickListener(
        w ->
            input(
                "language",
                "",
                language -> {
                  if (!CenterIndex.settings().optBoolean("consent", false)) {
                    toast(t("consent"));
                    return;
                  }
                  CenterTaskService.enqueue(Collections.singletonList(m), "translate", language);
                  toast(t("queue"));
                  dialog.dismiss();
                }));
    content.addView(translate);
    image.swipe =
        direction -> {
          dialog.dismiss();
          List<CenterData.Message> items = mediaMessages(screen == 5);
          int at = -1;
          for (int n = 0; n < items.size(); n++)
            if (items.get(n).qualified().equals(m.qualified())) at = n;
          int next = at + direction;
          if (at >= 0 && next >= 0 && next < items.size()) mediaDetails(items.get(next), 0);
        };
    if ((m.kind.equals("video") || m.kind.equals("round")) && !m.transcript.isEmpty()) {
      TextView chapters = label(t("chapters"), 14);
      content.addView(chapters);
      chapters.setOnClickListener(
          v -> {
            dialog.dismiss();
            drilldown = Collections.singletonList(m);
            query = t("chapterQuery");
            reportKind = 0;
            show(6);
          });
    }
    mediaDialog = dialog;
    dialog.show();
    dialog
        .getWindow()
        .setLayout(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
    if (CenterIndex.settings().optBoolean("secure", true))
      dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
  }

  private final class ZoomImage extends android.widget.ImageView {
    java.util.function.IntConsumer swipe;
    final Matrix matrix = new Matrix();
    float scale = 1, dx, dy, lastX, lastY;
    final android.view.ScaleGestureDetector detector;
    final android.view.GestureDetector gestures;

    ZoomImage(Context c) {
      super(c);
      setScaleType(ScaleType.MATRIX);
      detector =
          new android.view.ScaleGestureDetector(
              c,
              new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(android.view.ScaleGestureDetector d) {
                  scale = Math.max(1, Math.min(8, scale * d.getScaleFactor()));
                  transform();
                  return true;
                }
              });
      gestures =
          new android.view.GestureDetector(
              c,
              new android.view.GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onFling(
                    android.view.MotionEvent first,
                    android.view.MotionEvent last,
                    float vx,
                    float vy) {
                  if (first != null
                      && scale == 1
                      && Math.abs(vx) > 600
                      && Math.abs(last.getX() - first.getX()) > dp(70)
                      && swipe != null) {
                    swipe.accept(last.getX() < first.getX() ? 1 : -1);
                    return true;
                  }
                  return false;
                }

                @Override
                public boolean onDoubleTap(android.view.MotionEvent e) {
                  scale = scale > 1 ? 1 : 3;
                  dx = dy = 0;
                  transform();
                  return true;
                }
              });
    }

    @Override
    public void setImageBitmap(Bitmap b) {
      super.setImageBitmap(b);
      post(this::transform);
    }

    private void transform() {
      if (getDrawable() == null || getWidth() == 0) return;
      float base =
          Math.min(
              getWidth() / (float) Math.max(1, getDrawable().getIntrinsicWidth()),
              getHeight() / (float) Math.max(1, getDrawable().getIntrinsicHeight()));
      matrix.reset();
      matrix.postScale(base * scale, base * scale);
      matrix.postTranslate(
          (getWidth() - getDrawable().getIntrinsicWidth() * base * scale) / 2 + dx,
          (getHeight() - getDrawable().getIntrinsicHeight() * base * scale) / 2 + dy);
      setImageMatrix(matrix);
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent e) {
      detector.onTouchEvent(e);
      gestures.onTouchEvent(e);
      if (e.getAction() == 0) {
        lastX = e.getX();
        lastY = e.getY();
      } else if (e.getAction() == 2 && !detector.isInProgress() && scale > 1) {
        dx += e.getX() - lastX;
        dy += e.getY() - lastY;
        lastX = e.getX();
        lastY = e.getY();
        transform();
      }
      return true;
    }
  }

  private void playAt(CenterData.Message m, long ms) {
    TPAiClient.async(
        () -> CenterMedia.file(m, new java.util.concurrent.atomic.AtomicBoolean()),
        (file, e) -> {
          if (e != null) {
            error(e);
            return;
          }
          Intent i =
              new Intent(getParentActivity(), TelePremiumPlayerActivity.class)
                  .putExtra(TelePremiumPlayerActivity.EXTRA_PATH, file.getAbsolutePath())
                  .putExtra(TelePremiumPlayerActivity.EXTRA_NAME, m.sourceTitle)
                  .putExtra(TelePremiumPlayerActivity.EXTRA_MIME, m.mime)
                  .putExtra(TelePremiumPlayerActivity.EXTRA_START_POSITION, ms);
          getParentActivity().startActivity(i);
        });
  }

  private void pickCloud() {
    if (!org.telegram.messenger.telepremium.cloud.TelePremiumCloudSecuritySession.isUnlocked(
        ApplicationLoader.applicationContext)) {
      presentFragment(new TelePremiumCloudActivity(UserConfig.selectedAccount));
      return;
    }
    List<org.telegram.messenger.telepremium.cloud.CloudNode> nodes = new ArrayList<>();
    List<Integer> accounts = new ArrayList<>();
    List<String> names = new ArrayList<>();
    for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++)
      if (UserConfig.getInstance(a).isClientActivated() && !index.scope.excluded.contains(a)) {
        org.telegram.messenger.telepremium.cloud.TelePremiumCloudManager manager =
            org.telegram.messenger.telepremium.cloud.TelePremiumCloudManager.getInstance(a);
        for (org.telegram.messenger.telepremium.cloud.CloudNode node : manager.allFiles())
          if (manager.isVisible(node)
              && !manager.isProtected(node)
              && node.mimeType != null
              && (node.mimeType.startsWith("audio/")
                  || node.mimeType.startsWith("video/")
                  || node.mimeType.startsWith("image/")
                  || node.mimeType.equals("application/pdf"))) {
            nodes.add(node);
            accounts.add(a);
            names.add(node.name);
          }
      }
    choices(
        "cloud",
        names.toArray(new String[0]),
        i -> {
          org.telegram.messenger.telepremium.cloud.CloudNode node = nodes.get(i);
          CenterData.Message m = new CenterData.Message();
          m.account = accounts.get(i);
          m.owner = UserConfig.getInstance(m.account).getClientUserId();
          m.id = Math.abs(UUID.randomUUID().hashCode());
          m.source = "vault:" + node.id;
          m.sourceTitle = node.name;
          m.accountName =
              UserObject.getUserName(UserConfig.getInstance(m.account).getCurrentUser());
          m.cloudNode = node.id;
          m.mime = node.mimeType;
          m.bytes = node.size;
          m.kind =
              m.mime.startsWith("video/")
                  ? "video"
                  : m.mime.startsWith("audio/")
                      ? "audio"
                      : m.mime.startsWith("image/") ? "image" : "pdf";
          m.date = (int) (System.currentTimeMillis() / 1000);
          importMedia(m);
        });
  }

  private void importMedia(CenterData.Message m) {
    CenterData.Source source = new CenterData.Source();
    source.account = m.account;
    source.owner = m.owner;
    source.dialog = m.dialog;
    source.topic = m.topic;
    source.canonical = m.source;
    source.title = m.sourceTitle;
    source.accountName = m.accountName;
    source.type =
        m.dialog != 0
            ? (m.dialog > 0 ? "private" : "group")
            : m.cloudNode.isEmpty() ? "device" : "cloud";
    if (m.dialog > 0) index.scope.includePrivate = true;
    source.complete = true;
    index.scope.sources.add(source.key());
    index.scope.to = Math.max(index.scope.to, m.date);
    index.saveScope();
    CenterStore.IO.execute(
        () -> {
          try {
            CenterStore.get().put(m.account, m.owner, "source", source.key(), source.json());
            CenterStore.get().put(m.account, m.owner, "message", m.qualified(), m.json());
            AndroidUtilities.runOnUIThread(
                () -> {
                  pickedMedia.add(m.qualified());
                  if (pendingTranscription) {
                    pendingTranscription = false;
                    CenterTaskService.enqueue(Collections.singletonList(m), "transcribe");
                  }
                  reload();
                });
          } catch (Exception e) {
            AndroidUtilities.runOnUIThread(() -> error(e.getMessage()));
          }
        });
  }

  @Override
  public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
    super.onActivityResultFragment(requestCode, resultCode, data);
    if (requestCode != 72581
        || resultCode != android.app.Activity.RESULT_OK
        || data == null
        || data.getData() == null) return;
    android.net.Uri uri = data.getData();
    try {
      getParentActivity()
          .getContentResolver()
          .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    } catch (Exception ignored) {
    }
    CenterData.Message m = new CenterData.Message();
    m.account = UserConfig.selectedAccount;
    m.owner = UserConfig.getInstance(m.account).getClientUserId();
    m.id = Math.abs(UUID.randomUUID().hashCode());
    m.source = "device:" + UUID.randomUUID();
    m.accountName = UserObject.getUserName(UserConfig.getInstance(m.account).getCurrentUser());
    m.localUri = uri.toString();
    m.mime = getParentActivity().getContentResolver().getType(uri);
    if (m.mime == null) m.mime = "application/octet-stream";
    m.kind =
        m.mime.startsWith("audio/")
            ? "audio"
            : m.mime.startsWith("video/")
                ? "video"
                : m.mime.startsWith("image/")
                    ? "image"
                    : m.mime.equals("application/pdf") ? "pdf" : "document";
    m.date = (int) (System.currentTimeMillis() / 1000);
    m.sourceTitle = t("file");
    try (android.database.Cursor c =
        getParentActivity()
            .getContentResolver()
            .query(
                uri,
                new String[] {
                  android.provider.OpenableColumns.DISPLAY_NAME,
                  android.provider.OpenableColumns.SIZE
                },
                null,
                null,
                null)) {
      if (c != null && c.moveToFirst()) {
        m.sourceTitle = c.getString(0);
        m.bytes = c.getLong(1);
      }
    } catch (Exception ignored) {
    }
    importMedia(m);
  }

  private void statistics(boolean authors) {
    status.setText(t("loading"));
    int version = uiVersion;
    CenterData.Scope scope;
    try {
      scope = CenterData.Scope.parse(index.scope.json());
    } catch (Exception e) {
      error(e.getMessage());
      return;
    }
    List<CenterData.Message> snapshot = new ArrayList<>(index.readable());
    List<CenterData.Source> sources = new ArrayList<>(index.sources);
    CenterStore.IO.execute(
        () -> {
          try {
            CenterStats result =
                CenterStats.compute(snapshot, sources, scope, TimeZone.getDefault());
            AndroidUtilities.runOnUIThread(
                () -> {
                  if (!unlocked || !foreground || version != uiVersion) return;
                  statistics = result;
                  status.setText("");
                  if (authors) authors(result);
                  else activity(result);
                });
          } catch (Exception e) {
            AndroidUtilities.runOnUIThread(() -> error(e.getMessage()));
          }
        });
  }

  private void activity(CenterStats stats) {
    int totalMessages = 0, quietGroups = 0, activeGroups = 0;
    for (CenterStats.Group g : stats.groups) {
      totalMessages += g.messages;
      if (g.messages == 0) quietGroups++;
      else activeGroups++;
    }
    body.addView(
        ui.kpiRow(
            ui.kpi("" + totalMessages, t("messages"), ui.series(0), stats.days + " " + t("days"), ui.sub()),
            ui.kpi("" + activeGroups, t("active"), ui.series(1), "/ " + stats.groups.size(), ui.sub())));
    body.addView(
        ui.kpiRow(
            ui.kpi("" + stats.authors.size(), t("authors"), ui.series(2), "", ui.sub()),
            ui.kpi("" + quietGroups, t("quiet"), ui.series(3), "", quietGroups > 0 ? ui.warn() : ui.sub())));
    LinearLayout controls = ui.card(12, 10);
    controls.addView(ui.text(t("chart"), 12.5f, ui.sub(), true));
    String[] chartNames = {t("rank"), t("days"), t("heatmap"), t("content"), t("concentration")};
    android.widget.HorizontalScrollView chartRow = ui.chipRow();
    chartRow.getChildAt(0).setPadding(0, dp(6), 0, dp(6));
    for (int i = 0; i < chartNames.length; i++) {
      final int pick = i;
      ui.addChip(
          chartRow,
          ui.chip(
              chartNames[i],
              activityMode == i,
              v -> {
                activityMode = pick;
                show(2);
              }));
    }
    controls.addView(chartRow);
    controls.addView(ui.text(t("sort"), 12.5f, ui.sub(), true));
    String[] sortNames = {t("messages"), t("authors"), t("media"), t("growth")};
    android.widget.HorizontalScrollView sortRow = ui.chipRow();
    sortRow.getChildAt(0).setPadding(0, dp(6), 0, dp(4));
    for (int i = 0; i < sortNames.length; i++) {
      final int pick = i;
      ui.addChip(
          sortRow,
          ui.chip(
              sortNames[i],
              activitySort == i,
              v -> {
                activitySort = pick;
                show(2);
              }));
    }
    controls.addView(sortRow);
    LinearLayout controlButtons = new LinearLayout(getParentActivity());
    controlButtons.setPadding(0, dp(6), 0, 0);
    controlButtons.addView(
        ui.chip(
            t("groups") + (chartGroups.isEmpty() ? "" : " · " + chartGroups.size()),
            !chartGroups.isEmpty(),
            v -> {
              String[] names = new String[stats.groups.size()];
              boolean[] selected = new boolean[names.length];
              for (int i = 0; i < names.length; i++) {
                names[i] = stats.groups.get(i).title;
                selected[i] = chartGroups.contains(stats.groups.get(i).id);
              }
              dialogBuilder()
                  .setTitle(t("groups"))
                  .setMultiChoiceItems(
                      names,
                      selected,
                      (d, i, on) -> {
                        if (on && chartGroups.size() >= 4) {
                          ((AlertDialog) d).getListView().setItemChecked(i, false);
                          return;
                        }
                        if (on) chartGroups.add(stats.groups.get(i).id);
                        else chartGroups.remove(stats.groups.get(i).id);
                      })
                  .setPositiveButton(t("done"), (d, w) -> show(2))
                  .show();
            }));
    controls.addView(controlButtons);
    body.addView(controls, ui.cardParams());
    Comparator<CenterStats.Group> comparator =
        activitySort == 1
            ? Comparator.comparingInt(g -> g.authors.size())
            : activitySort == 2
                ? Comparator.comparingInt(g -> g.media)
                : activitySort == 3
                    ? Comparator.comparingDouble(
                        g -> g.previousComplete ? g.growth() : Double.NEGATIVE_INFINITY)
                    : Comparator.comparingInt(g -> g.messages);
    List<CenterStats.Group> ranked = new ArrayList<>(stats.groups);
    ranked.sort(comparator.reversed());
    List<CenterStats.Group> chosen = new ArrayList<>();
    for (CenterStats.Group g : ranked)
      if (chartGroups.isEmpty() ? chosen.size() < 4 : chartGroups.contains(g.id)) chosen.add(g);
    if (!chosen.isEmpty()) {
      ActivityChart chart = new ActivityChart(getParentActivity(), stats, chosen, activityMode);
      LinearLayout chartCard = ui.card(6, 10);
      chartCard.addView(chart, new LinearLayout.LayoutParams(-1, dp(activityMode == 2 ? 280 : 320)));
      body.addView(chartCard, ui.cardParams());
      TextView exportChart =
          ui.button(t("export"), false, R.drawable.tp_aic_share, v -> shareChart(chart));
      TextView chartReport =
          ui.button(
              t("chartReport"),
              true,
              R.drawable.tp_aic_reports,
              v -> {
            int mode = activityMode, version = uiVersion;
            List<CenterData.Source> selectedSources = new ArrayList<>(index.sources);
            TPAiClient.async(
                () ->
                    CenterChartReport.build(
                        stats, chosen, mode, selectedSources, TelePremiumAnalyzerActivity::t),
                (snapshot, failure) -> {
                  if (!unlocked || !foreground || version != uiVersion) return;
                  if (failure != null) error(failure);
                  else {
                    report = snapshot;
                    screen = 7;
                    archiveDetail = true;
                    show(7);
                  }
                });
          });
      body.addView(ui.buttonRow(exportChart, chartReport), margins(-1, -2, 12, 0, 12, 0));
    }
    heading("table");
    int topMessages = 1;
    for (CenterStats.Group g : ranked) topMessages = Math.max(topMessages, g.messages);
    for (CenterStats.Group g : ranked) {
      boolean rising = g.previousComplete && g.growth() > 0;
      boolean falling = g.previousComplete && g.growth() < 0;
      String growth =
          g.previousComplete
              ? (g.previous == 0 && g.messages > 0
                  ? t("new")
                  : String.format(Locale.getDefault(), "%+.1f%%", g.growth() * 100))
              : t("partial");
      LinearLayout card = ui.clickableCard(14, 12, v -> messageDetails(g.evidence));
      LinearLayout head = new LinearLayout(getParentActivity());
      head.setGravity(Gravity.CENTER_VERTICAL);
      TextView title = ui.text(g.title, 15, ui.text(), true);
      title.setSingleLine(true);
      title.setEllipsize(android.text.TextUtils.TruncateAt.END);
      head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
      head.addView(ui.pill(growth, rising ? ui.good() : falling ? ui.bad() : ui.sub()));
      card.addView(head);
      LinearLayout meterLine = new LinearLayout(getParentActivity());
      meterLine.setGravity(Gravity.CENTER_VERTICAL);
      meterLine.setPadding(0, dp(8), 0, dp(6));
      meterLine.addView(
          ui.meter(g.messages / (float) topMessages, ui.series(Math.max(0, chosen.indexOf(g))), 7),
          new LinearLayout.LayoutParams(0, dp(7), 1));
      TextView count = ui.text("" + g.messages, 15, ui.text(), true);
      count.setPadding(dp(10), 0, 0, 0);
      meterLine.addView(count);
      card.addView(meterLine);
      card.addView(
          ui.text(
              t("authors") + ": " + g.authors.size()
                  + " · " + t("media") + ": " + g.media
                  + " · " + t("average") + ": "
                  + String.format(Locale.getDefault(), "%.1f", g.messages / (double) stats.days)
                  + " · " + t("concentration") + ": " + g.concentration
                  + (g.currentComplete ? "" : " · " + t("partial")),
              12,
              ui.sub(),
              false));
      body.addView(card, ui.cardParams());
    }
    if (activityMode == 1) {
      heading("days");
      for (CenterStats.Group g : chosen)
        for (Map.Entry<String, Integer> day : g.daily.entrySet())
          info(g.title + " · " + day.getKey() + " · " + day.getValue());
    }
    if (activityMode == 2 && !chosen.isEmpty()) {
      heading("heatmap");
      CenterStats.Group g = chosen.get(0);
      String[] weekdays = new java.text.DateFormatSymbols().getShortWeekdays();
      for (int d = 0; d < 7; d++) {
        StringBuilder cells = new StringBuilder(weekdays[(d + 1) % 7 + 1]);
        for (int h = 0; h < 24; h++)
          cells.append(" · ").append(h).append(": ").append(g.heat[d][h]);
        info(cells.toString());
      }
    }
    if (activityMode == 3) {
      heading("content");
      for (CenterStats.Group g : chosen)
        for (Map.Entry<String, Integer> type : g.types.entrySet())
          info(
              g.title
                  + " · "
                  + t(type.getKey().equals("link") ? "links" : type.getKey())
                  + " · "
                  + type.getValue());
    }
    heading("quiet");
    for (CenterStats.Group g : ranked)
      if (g.messages == 0)
        info(
            g.title
                + " · "
                + (g.currentComplete ? t("quiet") : t("partial"))
                + " · "
                + (g.last > 0 ? new Date(g.last * 1000).toString() : "—"));
    heading("anomalies");
    for (Map.Entry<String, List<CenterData.Message>> anomaly : stats.anomalies.entrySet())
      row(anomaly.getKey(), () -> messageDetails(anomaly.getValue()));
    heading("links");
    for (Map.Entry<String, List<CenterData.Message>> domain : stats.domains.entrySet())
      row(
          domain.getKey() + " · " + domain.getValue().size(),
          () -> messageDetails(domain.getValue()));
    for (CenterLinks.Link link : stats.urls.values()) {
      row(
          (link.title.isEmpty() ? link.url : link.title)
              + " · "
              + link.domain
              + " · "
              + link.evidence.size()
              + "\n"
              + t("firstInScope")
              + ": "
              + link.first.authorName
              + " · "
              + link.first.sourceTitle
              + " · "
              + new Date(link.first.date * 1000L),
          () -> messageDetails(link.evidence));
    }
    heading("forwarded");
    for (List<CenterData.Message> copies : stats.forwards.values()) {
      copies.sort(Comparator.comparingInt(message -> message.date));
      CenterData.Message first = copies.get(0);
      row(
          first.forwardSource
              + (first.forwardVia.isEmpty() ? "" : " · " + first.forwardVia)
              + " · "
              + copies.size()
              + "\n"
              + t("repostChronology"),
          () -> messageDetails(copies));
    }
    heading("discussed");
    List<CenterData.Message> discussed = new ArrayList<>(stats.messages);
    discussed.sort(
        Comparator.comparingInt((CenterData.Message m) -> m.reactions + m.replies).reversed());
    for (int n = 0; n < Math.min(20, discussed.size()); n++) {
      CenterData.Message m = discussed.get(n);
      if (m.reactions + m.replies > 0)
        row(
            m.sourceTitle
                + " · "
                + m.authorName
                + " · "
                + (m.reactions + m.replies)
                + "\n"
                + m.text,
            () -> openEvidence(m));
    }
    button("compare", this::loadPreviousPeriod);
  }

  private void loadPreviousPeriod() {
    CenterIndex wider = new CenterIndex();
    wider.sources.addAll(index.sources);
    wider.messages.addAll(index.messages);
    try {
      wider.scope = CenterData.Scope.parse(index.scope.json());
      long span = Math.max(1, wider.scope.to - wider.scope.from + 1);
      wider.scope.from -= span;
      wider.scope.sinceRead = false;
    } catch (Exception e) {
      error(e.getMessage());
      return;
    }
    if (sync != null) sync.cancel();
    sync =
        new CenterSync(
            wider,
            e -> {
              error(e);
              reload();
            });
    status.setText(t("loading"));
    sync.load();
  }

  private void messageDetails(List<CenterData.Message> messages) {
    uiVersion++;
    clearBody();
    heading("details");
    info(t("messages") + ": " + messages.size());
    button(
        "summaries",
        () -> {
          drilldown = new ArrayList<>(messages);
          authorReport = false;
          show(1);
        });
    for (int i = 0; i < Math.min(200, messages.size()); i++) {
      CenterData.Message m = messages.get(i);
      row(
          m.authorName
              + " · "
              + m.sourceTitle
              + " · "
              + m.accountName
              + " · "
              + new Date(m.date * 1000L)
              + "\n"
              + m.text,
          () -> openEvidence(m));
    }
  }

  private void authors(CenterStats stats) {
    LinearLayout controls = ui.card(12, 10);
    EditText search = ui.field(t("search"), authorSearch, 1);
    search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
    search.setOnEditorActionListener(
        (v, action, event) -> {
          authorSearch = v.getText().toString().trim();
          authorListPage = 0;
          show(3);
          return true;
        });
    controls.addView(search, new LinearLayout.LayoutParams(-1, -2));
    String[] sortNames = {t("messages"), t("media"), t("groups"), t("growth")};
    android.widget.HorizontalScrollView sortRow = ui.chipRow();
    sortRow.getChildAt(0).setPadding(0, dp(8), 0, dp(2));
    for (int i = 0; i < sortNames.length; i++) {
      final int pick = i;
      ui.addChip(
          sortRow,
          ui.chip(
              sortNames[i],
              authorSort == i,
              v -> {
                authorSort = pick;
                authorListPage = 0;
                show(3);
              }));
    }
    controls.addView(sortRow);
    body.addView(controls, ui.cardParams());
    primary(
          "compareAuthors",
          R.drawable.tp_aic_authors,
          () -> {
            if (comparedAuthors.isEmpty()) {
              toast(tr("Switch on two or more authors first.", "Сначала включите двух или больше авторов."));
              return;
            }
            clearBody();
            heading("compareAuthors");
            info(t("authorWarning"));
            for (CenterStats.Author a : stats.authors)
              if (comparedAuthors.contains(a.id)) authorNumbers(a, stats);
          });
    if (pendingAuthor != Long.MIN_VALUE) {
      long selected = pendingAuthor;
      pendingAuthor = Long.MIN_VALUE;
      for (CenterStats.Author author : stats.authors)
        if (author.id == selected) {
          authorProfile(author, stats);
          return;
        }
    }
    List<CenterStats.Author> authors = new ArrayList<>(stats.authors);
    Comparator<CenterStats.Author> comparator =
        authorSort == 1
            ? Comparator.comparingInt(a -> a.media)
            : authorSort == 2
                ? Comparator.comparingInt(a -> a.groups.size())
                : authorSort == 3
                    ? Comparator.comparingInt(a -> a.count - a.previous)
                    : Comparator.comparingInt(a -> a.count);
    authors.sort(comparator.reversed());
    int rendered = 0;
    int topAuthor = 1;
    for (CenterStats.Author a : authors) topAuthor = Math.max(topAuthor, a.count);
    for (CenterStats.Author a : authors)
      if (a.name.toLowerCase(Locale.ROOT).contains(authorSearch.toLowerCase(Locale.ROOT))
          && rendered++ >= authorListPage * 40
          && rendered <= (authorListPage + 1) * 40) {
        LinearLayout card = ui.clickableCard(12, 10, v -> authorProfile(a, stats));
        LinearLayout line = new LinearLayout(getParentActivity());
        line.setGravity(Gravity.CENTER_VERTICAL);
        int account = a.accounts.isEmpty() ? UserConfig.selectedAccount : a.accounts.iterator().next();
        avatar(line, account, a.id);
        LinearLayout col = new LinearLayout(getParentActivity());
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = ui.text(a.name, 15, ui.text(), true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(name);
        col.addView(
            ui.text(
                a.groups.size() + " " + t("groups") + " · " + t("media") + ": " + a.media
                    + (a.own ? " · " + t("ownAuthor") : ""),
                12,
                ui.sub(),
                false));
        line.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        TextView count = ui.text("" + a.count, 17, ui.text(), true);
        count.setPadding(dp(8), 0, dp(8), 0);
        line.addView(count);
        final Switch compare =
            ui.switchView(
                comparedAuthors.contains(a.id),
                b -> {
                  if (b) comparedAuthors.add(a.id);
                  else comparedAuthors.remove(a.id);
                });
        line.addView(compare);
        card.addView(line);
        LinearLayout.LayoutParams meterParams = new LinearLayout.LayoutParams(-1, dp(5));
        meterParams.setMargins(dp(2), dp(8), dp(2), 0);
        card.addView(ui.meter(a.count / (float) topAuthor, ui.accent(), 5), meterParams);
        body.addView(card, ui.cardParams());
      }
    long matching =
        authors.stream()
            .filter(
                a ->
                    a.name.toLowerCase(Locale.ROOT).contains(authorSearch.toLowerCase(Locale.ROOT)))
            .count();
    if (authorListPage > 0)
      button(
          "previous",
          () -> {
            authorListPage--;
            show(3);
          });
    if ((authorListPage + 1) * 40 < matching)
      button(
          "next",
          () -> {
            authorListPage++;
            show(3);
          });
  }

  private void authorNumbers(CenterStats.Author a, CenterStats stats) {
    info(
        a.name
            + " · "
            + t("messages")
            + ": "
            + a.count
            + " · "
            + t("media")
            + ": "
            + a.media
            + " · "
            + t("links")
            + ": "
            + a.links
            + " · "
            + t("replies")
            + ": "
            + a.replies
            + " · "
            + t("length")
            + ": "
            + (a.count == 0 ? 0 : a.characters / a.count));
    for (CenterStats.Group g : stats.groups)
      if (a.groups.contains(g.id)) {
        long n = g.peerAuthorCounts.getOrDefault(a.id, 0);
        info(
            g.title
                + ": "
                + n
                + " / "
                + g.allMessages
                + " · "
                + (g.allMessages == 0 ? 0 : 100 * n / g.allMessages)
                + "%");
      }
  }

  private void authorProfile(CenterStats.Author a, CenterStats stats) {
    uiVersion++;
    if (profileAuthor != a.id) {
      profileAuthor = a.id;
      authorPage = 0;
      authorKind = "";
    }
    clearBody();
    info(t("authorWarning"));
    TextView name = row(a.name, null);
    name.setTypeface(AndroidUtilities.bold());
    authorNumbers(a, stats);
    button(
        "ask",
        () -> {
          drilldown = new ArrayList<>(a.publications);
          authorReport = true;
          show(6);
        });
    authorSnapshot = null;
    LinearLayout overview = new LinearLayout(getParentActivity());
    overview.setOrientation(1);
    body.addView(overview);
    TextView loading = label(t("loading"), 14);
    overview.addView(loading);
    int profileVersion = uiVersion;
    TPAiClient.async(
        () -> CenterAuthorProfile.build(a, stats, TelePremiumAnalyzerActivity::t),
        (profile, failure) -> {
          if (!unlocked || !foreground || profileVersion != uiVersion) return;
          overview.removeAllViews();
          if (failure != null) {
            overview.addView(label(t("error") + ": " + failure, 14));
            return;
          }
          authorSnapshot = profile;
          CenterReports.Claim paragraph = profile.claims.get(0);
          overview.addView(label(paragraph.text, 15));
          for (CenterReports.Claim claim :
              profile.claims.subList(1, Math.min(profile.claims.size(), 2 + stats.groups.size()))) {
            if (claim.title.startsWith(t("topic")) || claim.title.startsWith(t("newTopic"))) break;
            overview.addView(label(claim.title + "\n" + claim.text, 14));
          }
          TextView sources = label(t("evidence") + " (" + paragraph.evidence.size() + ")", 14);
          overview.addView(sources);
          LinearLayout evidence = new LinearLayout(getParentActivity());
          evidence.setOrientation(1);
          overview.addView(evidence);
          sources.setOnClickListener(
              v -> {
                if (evidence.getChildCount() > 0) evidence.removeAllViews();
                else
                  for (CenterReports.Evidence proof : paragraph.evidence) {
                    TextView line =
                        label(
                            proof.message.sourceTitle
                                + " · "
                                + proof.message.accountName
                                + "\n"
                                + proof.quote,
                            14);
                    line.setOnClickListener(w -> openEvidence(proof.message));
                    evidence.addView(line);
                  }
              });
        });
    button(
        "archive",
        () -> {
          if (authorSnapshot == null) {
            toast(t("loading"));
            return;
          }
          persistReport(authorSnapshot, "report");
        });
    button(
        "export",
        () -> {
          if (authorSnapshot == null) {
            toast(t("loading"));
            return;
          }
          report = authorSnapshot;
          exportReport(false, false);
        });
    button(
        "compare",
        () -> {
          pendingAuthor = a.id;
          loadPreviousPeriod();
        });
    heading("hourlyActivity");
    StringBuilder hours = new StringBuilder();
    for (int hour = 0; hour < 24; hour++)
      hours.append(
          String.format(
              Locale.getDefault(),
              "%02d:00 · %d%s",
              hour,
              a.hours[hour],
              hour % 4 == 3 ? "\n" : "   "));
    info(hours.toString());
    heading("topics");
    List<Map.Entry<String, Integer>> topics = new ArrayList<>(a.topics.entrySet());
    topics.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
    for (Map.Entry<String, Integer> topic : topics)
      row(
          (CenterAuthorProfile.newTopic(a, stats, topic.getKey()) ? t("newTopic") + ": " : "")
              + topic.getKey()
              + " · "
              + topic.getValue()
              + " · "
              + t("previousPeriod")
              + ": "
              + (CenterAuthorProfile.baselineComplete(a, stats)
                  ? a.previousTopics.getOrDefault(topic.getKey(), 0)
                  : t("partial")),
          () -> {
            List<CenterData.Message> hits = new ArrayList<>();
            for (CenterData.Message m : a.publications)
              if (m.text.toLowerCase(Locale.ROOT).contains(topic.getKey().toLowerCase(Locale.ROOT)))
                hits.add(m);
            messageDetails(hits);
          });
    if (!CenterAuthorProfile.baselineComplete(a, stats)) info(t("baselineMissing"));
    heading("weeklyActivity");
    Map<String, Integer> weeks = new TreeMap<>();
    java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("YYYY-'W'ww", Locale.ROOT);
    for (CenterData.Message m : a.publications) {
      String week = f.format(new Date(m.date * 1000L));
      weeks.put(week, weeks.getOrDefault(week, 0) + 1);
    }
    for (Map.Entry<String, Integer> week : weeks.entrySet())
      info(week.getKey() + " · " + week.getValue());
    button(
        "media",
        () -> {
          index.scope.authors.clear();
          index.scope.authors.add(a.id);
          index.saveScope();
          show(4);
        });
    heading("publications");
    button(
        "type",
        () ->
            choices(
                "type",
                new String[] {t("all"), t("text"), t("photo"), t("video"), t("links")},
                i -> {
                  authorKind = new String[] {"", "text", "photo", "video", "link"}[i];
                  authorPage = 0;
                  authorProfile(a, stats);
                }));
    List<CenterData.Message> feed = new ArrayList<>();
    for (CenterData.Message m : a.publications)
      if (authorKind.isEmpty()
          || authorKind.equals(m.kind)
          || (authorKind.equals("photo") && m.kind.equals("image"))) feed.add(m);
    for (int i = authorPage * 40; i < Math.min((authorPage + 1) * 40, feed.size()); i++) {
      CenterData.Message m = feed.get(i);
      row(
          new Date(m.date * 1000L)
              + " · "
              + m.sourceTitle
              + " · "
              + m.accountName
              + "\n"
              + m.text
              + "\n"
              + m.description,
          () -> openEvidence(m));
      if (!m.frameTimes.isEmpty()) {
        android.widget.ImageView picture = new android.widget.ImageView(getParentActivity());
        picture.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        body.addView(picture, new LinearLayout.LayoutParams(-1, dp(150)));
        CenterThumbnails.load(
            m,
            m.frameTimes.get(0),
            0,
            b -> {
              if (b != null) picture.setImageBitmap(b);
            });
        picture.setOnClickListener(w -> mediaDetails(m, 0));
      }
    }
    if (authorPage > 0)
      button(
          "previous",
          () -> {
            authorPage--;
            authorProfile(a, stats);
          });
    if ((authorPage + 1) * 40 < feed.size())
      button(
          "next",
          () -> {
            authorPage++;
            authorProfile(a, stats);
          });
  }

  private void shareChart(View chart) {
    Bitmap image =
        Bitmap.createBitmap(
            Math.max(1, chart.getWidth()), Math.max(1, chart.getHeight()), Bitmap.Config.ARGB_8888);
    chart.draw(new Canvas(image));
    try {
      java.io.File directory =
          new java.io.File(ApplicationLoader.applicationContext.getCacheDir(), "sharing");
      directory.mkdirs();
      java.io.File file = new java.io.File(directory, "AI-Center-chart.png");
      try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
        image.compress(Bitmap.CompressFormat.PNG, 100, out);
      }
      android.net.Uri uri =
          androidx.core.content.FileProvider.getUriForFile(
              getParentActivity(), getParentActivity().getPackageName() + ".provider", file);
      getParentActivity()
          .startActivity(
              Intent.createChooser(
                  new Intent(Intent.ACTION_SEND)
                      .setType("image/png")
                      .putExtra(Intent.EXTRA_STREAM, uri)
                      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                  t("export")));
    } catch (Exception e) {
      error(e.getMessage());
    } finally {
      image.recycle();
    }
  }

  private final class ActivityChart extends View {
    final Paint paint = new Paint(3);
    final CenterStats stats;
    final List<CenterStats.Group> groups;
    final int mode;
    final List<android.graphics.RectF> hits = new ArrayList<>();
    final List<List<CenterData.Message>> data = new ArrayList<>();

    ActivityChart(Context c, CenterStats s, List<CenterStats.Group> g, int m) {
      super(c);
      stats = s;
      groups = g;
      mode = m;
      setContentDescription(t("chart") + " · " + s.messages.size() + " · " + t("table"));
    }

    void hit(float left, float top, float right, float bottom, List<CenterData.Message> rows) {
      hits.add(new android.graphics.RectF(left, top, right, bottom));
      data.add(rows);
    }

    int color(int i) {
      return ui.series(i);
    }

    void text(Canvas c, String label, float x, float y) {
      text(c, label, x, y, ui.text(), 11.5f, Paint.Align.LEFT);
    }

    void text(Canvas c, String label, float x, float y, int color, float sp, Paint.Align align) {
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(color);
      paint.setTextSize(dp(sp));
      paint.setTextAlign(align);
      c.drawText(label, x, y, paint);
      paint.setTextAlign(Paint.Align.LEFT);
    }

    void bar(Canvas c, float l, float t, float r, float b, int color) {
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(color);
      if (r - l < 1) r = l + 1;
      c.drawRoundRect(l, t, r, b, Math.min(dp(5), (b - t) / 2), Math.min(dp(5), (b - t) / 2), paint);
    }

    void legend(Canvas c, List<String> names, float x, float y, float limit) {
      float cx = x;
      for (int i = 0; i < names.size(); i++) {
        String name = names.get(i).length() > 18 ? names.get(i).substring(0, 18) + "…" : names.get(i);
        paint.setTextSize(dp(11));
        float w = paint.measureText(name) + dp(22);
        if (cx + w > limit) {
          cx = x;
          y += dp(16);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color(i));
        c.drawRoundRect(cx, y - dp(9), cx + dp(10), y + dp(1), dp(3), dp(3), paint);
        text(c, name, cx + dp(15), y, ui.sub(), 11, Paint.Align.LEFT);
        cx += w + dp(6);
      }
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      hits.clear();
      data.clear();
      canvas.drawColor(ui.card());
      float left = dp(10), right = getWidth() - dp(10), width = right - left;
      float top = dp(8), bottom = getHeight() - dp(8), height = bottom - top;
      if (mode == 0 || mode == 3 || mode == 4) {
        List<String> legendNames = new ArrayList<>();
        if (mode == 3) {
          Set<String> kinds = new LinkedHashSet<>();
          for (CenterStats.Group g : groups) kinds.addAll(g.types.keySet());
          for (String kind : kinds) legendNames.add(t(kind.equals("link") ? "links" : kind));
          bottom -= dp(18) * (1 + legendNames.size() / 4);
          height = bottom - top;
        }
        int max = 1;
        for (CenterStats.Group g : groups)
          max = Math.max(max, mode == 4 ? g.concentration : mode == 3 ? g.messages : g.messages);
        float row = height / Math.max(1, groups.size());
        int n = 0;
        for (CenterStats.Group g : groups) {
          float y = top + n * row;
          String title = g.title.length() > 34 ? g.title.substring(0, 34) + "…" : g.title;
          text(canvas, title, left, y + dp(14), ui.text(), 12.5f, Paint.Align.LEFT);
          float barTop = y + dp(22), barBottom = Math.min(y + row - dp(10), barTop + dp(24));
          float trackEnd = right - dp(46);
          paint.setStyle(Paint.Style.FILL);
          paint.setColor(TelePremiumCenterUi.alpha(ui.sub(), 36));
          canvas.drawRoundRect(left, barTop, trackEnd, barBottom, dp(5), dp(5), paint);
          float x = left;
          int value = mode == 4 ? g.concentration : g.messages;
          if (mode == 3) {
            int k = 0;
            for (Map.Entry<String, Integer> type : g.types.entrySet()) {
              float edge = x + (trackEnd - left) * type.getValue() / max;
              if (edge > x) bar(canvas, x, barTop, edge, barBottom, color(k));
              k++;
              x = edge;
            }
          } else {
            int fill =
                mode == 0 && g.previousComplete
                    ? (g.growth() >= 0 ? ui.good() : ui.bad())
                    : color(n);
            bar(canvas, left, barTop, left + (trackEnd - left) * value / max, barBottom, fill);
          }
          text(
              canvas,
              mode == 4 ? value + "%" : "" + value,
              right,
              (barTop + barBottom) / 2 + dp(4.5f),
              ui.text(),
              13,
              Paint.Align.RIGHT);
          hit(left, y, right, y + row, g.evidence);
          n++;
        }
        if (mode == 3) {
          legend(canvas, legendNames, left, getHeight() - dp(8) - dp(16) * (legendNames.size() / 4), right);
        }
      } else if (mode == 1) {
        SortedSet<String> dates = new TreeSet<>();
        int max = 1;
        for (CenterStats.Group g : groups) {
          dates.addAll(g.daily.keySet());
          for (int n : g.daily.values()) max = Math.max(max, n);
        }
        List<String> days = new ArrayList<>(dates);
        float plotLeft = left + dp(26), plotTop = top + dp(8), plotRight = right - dp(6);
        float plotBottom = getHeight() - dp(54);
        float plotHeight = plotBottom - plotTop, plotWidth = plotRight - plotLeft;
        paint.setStrokeWidth(dp(1));
        for (int g = 0; g <= 4; g++) {
          float y = plotBottom - plotHeight * g / 4f;
          paint.setStyle(Paint.Style.STROKE);
          paint.setColor(ui.line());
          canvas.drawLine(plotLeft, y, plotRight, y, paint);
          text(canvas, "" + Math.round(max * g / 4f), left, y + dp(4), ui.sub(), 10.5f, Paint.Align.LEFT);
        }
        if (!days.isEmpty()) {
          text(canvas, days.get(0), plotLeft, plotBottom + dp(16), ui.sub(), 10.5f, Paint.Align.LEFT);
          text(canvas, days.get(days.size() - 1), plotRight, plotBottom + dp(16), ui.sub(), 10.5f, Paint.Align.RIGHT);
        }
        List<String> names = new ArrayList<>();
        for (int g = 0; g < groups.size(); g++) {
          CenterStats.Group group = groups.get(g);
          names.add(group.title);
          Path line = new Path(), area = new Path();
          float lastX = plotLeft, firstX = plotLeft;
          for (int i = 0; i < days.size(); i++) {
            float x = plotLeft + plotWidth * i / Math.max(1, days.size() - 1),
                y = plotBottom - plotHeight * group.daily.getOrDefault(days.get(i), 0) / max;
            if (i == 0) {
              line.moveTo(x, y);
              area.moveTo(x, plotBottom);
              area.lineTo(x, y);
              firstX = x;
            } else {
              line.lineTo(x, y);
              area.lineTo(x, y);
            }
            lastX = x;
            List<CenterData.Message> rows =
                group.dayEvidence.getOrDefault(days.get(i), Collections.emptyList());
            hit(x - dp(12), y - dp(12), x + dp(12), y + dp(12), rows);
          }
          if (!days.isEmpty() && g == 0) {
            area.lineTo(lastX, plotBottom);
            area.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(TelePremiumCenterUi.alpha(color(0), 34));
            canvas.drawPath(area, paint);
          }
          paint.setColor(color(g));
          paint.setStyle(Paint.Style.STROKE);
          paint.setStrokeWidth(dp(2.2f));
          paint.setStrokeJoin(Paint.Join.ROUND);
          paint.setStrokeCap(Paint.Cap.ROUND);
          canvas.drawPath(line, paint);
          if (days.size() <= 31) {
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < days.size(); i++) {
              float x = plotLeft + plotWidth * i / Math.max(1, days.size() - 1),
                  y = plotBottom - plotHeight * group.daily.getOrDefault(days.get(i), 0) / max;
              canvas.drawCircle(x, y, dp(2.6f), paint);
            }
          }
        }
        paint.setStyle(Paint.Style.FILL);
        legend(canvas, names, left, getHeight() - dp(16), right);
      } else if (mode == 2 && !groups.isEmpty()) {
        CenterStats.Group group = groups.get(0);
        int max = 1;
        for (int[] day : group.heat) for (int count : day) max = Math.max(max, count);
        text(canvas, group.title, left, top + dp(12), ui.text(), 12.5f, Paint.Align.LEFT);
        float gridLeft = left + dp(28), gridTop = top + dp(22);
        float cw = (right - gridLeft) / 24, ch = (getHeight() - gridTop - dp(24)) / 7;
        for (int d = 0; d < 7; d++)
          for (int h = 0; h < 24; h++) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(
                group.heat[d][h] == 0
                    ? TelePremiumCenterUi.alpha(ui.sub(), 28)
                    : TelePremiumCenterUi.alpha(ui.accent(), 60 + 195 * group.heat[d][h] / max));
            float x = gridLeft + h * cw, y = gridTop + d * ch;
            canvas.drawRoundRect(x + 1, y + 1, x + cw - 1, y + ch - 1, dp(3), dp(3), paint);
            List<CenterData.Message> rows =
                group.hourEvidence.getOrDefault(d + ":" + h, Collections.emptyList());
            hit(x, y, x + cw, y + ch, rows);
          }
        String[] weekdays = new java.text.DateFormatSymbols().getShortWeekdays();
        for (int d = 0; d < 7; d++)
          text(canvas, weekdays[(d + 1) % 7 + 1], left, gridTop + (d + .65f) * ch, ui.sub(), 10.5f, Paint.Align.LEFT);
        for (int h = 0; h < 24; h += 3)
          text(canvas, "" + h, gridLeft + (h + .1f) * cw, getHeight() - dp(8), ui.sub(), 10.5f, Paint.Align.LEFT);
      }
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
      if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
        for (int i = 0; i < hits.size(); i++)
          if (hits.get(i).contains(event.getX(), event.getY())) {
            messageDetails(data.get(i));
            return true;
          }
      }
      return true;
    }
  }

  private void reportBuilder() {
    clearBody();
    heading("blocks");
    CenterExport.Options options = exportOptions();
    toggle(
        "anonymize",
        options.anonymize,
        b -> {
          options.anonymize = b;
          saveExportOptions(options);
        });
    toggle(
        "noImages",
        !options.images,
        b -> {
          options.images = !b;
          saveExportOptions(options);
        });
    info(t("anonymizeHint"));
    String[] keys = {"main", "charts", "authors", "media", "transcripts", "unclear"};
    for (String key : keys) {
      CheckBox check = new CheckBox(getParentActivity());
      check.setText(t(key.equals("unclear") ? "unknown" : key));
      check.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
      check.setChecked(options.blocks.contains(key));
      check.setOnCheckedChangeListener(
          (v, on) -> {
            options.blocks.remove(key);
            if (on) options.blocks.add(key);
            saveExportOptions(options);
          });
      body.addView(check);
    }
    button(
        "order",
        () -> {
          String[] names = new String[options.blocks.size()];
          for (int i = 0; i < names.length; i++)
            names[i] = (i + 1) + " · " + t(options.blocks.get(i));
          choices(
              "order",
              names,
              i -> {
                if (i > 0) {
                  Collections.swap(options.blocks, i, i - 1);
                  saveExportOptions(options);
                }
                reportBuilder();
              });
        });
    button("done", () -> show(screen));
  }

  private void saveExportOptions(CenterExport.Options options) {
    try {
      CenterIndex.setting("exportOptions", options.json());
    } catch (Exception e) {
      error(e.getMessage());
    }
  }

  private void exportReport(boolean cloud, boolean preview) {
    if (report == null) {
      toast(t("empty"));
      return;
    }
    String[] formats = {"pdf", "html", "md", "txt", "csv", "png", "srt", "vtt"};
    choices(
        "formats",
        new String[] {"PDF", "HTML", "Markdown", "TXT", "CSV", "PNG", "SRT", "VTT"},
        i -> {
          String format = formats[i];
          if (i >= 6) {
            List<CenterData.Message> media = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (CenterData.Message m : index.selected())
              if (!m.transcript.isEmpty()) {
                media.add(m);
                names.add(m.sourceTitle);
              }
            choices(
                "transcriber",
                names.toArray(new String[0]),
                which -> {
                  TPAiClient.async(
                      () -> CenterFiles.transcript(media.get(which), format),
                      (file, e) -> {
                        if (e != null) error(e);
                        else deliver(file, format, preview, cloud);
                      });
                });
            return;
          }
          CenterReports.Report selected = report;
          CenterExport.Options options = exportOptions();
          status.setText(t("loading"));
          TPAiClient.async(
              () -> CenterFiles.export(selected, options, format),
              (file, e) -> {
                status.setText("");
                if (e != null) {
                  error(e);
                  return;
                }
                deliver(file, format, preview, cloud);
              });
        });
  }

  private void deliver(java.io.File file, String format, boolean preview, boolean cloud) {
    if (cloud) {
      saveInCloud(file);
      return;
    }
    try {
      android.net.Uri uri =
          androidx.core.content.FileProvider.getUriForFile(
              getParentActivity(), getParentActivity().getPackageName() + ".provider", file);
      Intent intent = new Intent(preview ? Intent.ACTION_VIEW : Intent.ACTION_SEND);
      if (preview) intent.setDataAndType(uri, CenterFiles.mime(format));
      else intent.setType(CenterFiles.mime(format)).putExtra(Intent.EXTRA_STREAM, uri);
      intent.setClipData(
          ClipData.newUri(getParentActivity().getContentResolver(), file.getName(), uri));
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      getParentActivity()
          .startActivity(preview ? intent : Intent.createChooser(intent, t("export")));
    } catch (Exception e) {
      error(e.getMessage());
    }
  }

  private void saveInCloud(java.io.File file) {
    if (!org.telegram.messenger.telepremium.cloud.TelePremiumCloudSecuritySession.isUnlocked(
        ApplicationLoader.applicationContext)) {
      presentFragment(new TelePremiumCloudActivity(UserConfig.selectedAccount));
      return;
    }
    List<Integer> accounts = new ArrayList<>();
    List<String> names = new ArrayList<>();
    for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++)
      if (UserConfig.getInstance(a).isClientActivated() && !index.scope.excluded.contains(a)) {
        accounts.add(a);
        names.add(UserObject.getUserName(UserConfig.getInstance(a).getCurrentUser()));
      }
    choices(
        "account",
        names.toArray(new String[0]),
        i ->
            dialogBuilder()
                .setMessage(t("saveCloud") + " · " + names.get(i) + " · " + file.length())
                .setPositiveButton(
                    t("confirm"),
                    (d, w) -> {
                      int account = accounts.get(i);
                      org.telegram.messenger.telepremium.cloud.TelePremiumCloudManager manager =
                          org.telegram.messenger.telepremium.cloud.TelePremiumCloudManager
                              .getInstance(account);
                      manager.bootstrap(
                          e -> {
                            if (e != null) {
                              error(e);
                              return;
                            }
                            new org.telegram.messenger.telepremium.cloud.CloudPayloadRepository(
                                    account)
                                .importUri(
                                    android.net.Uri.fromFile(file),
                                    "root",
                                    file.getName(),
                                    null,
                                    (node, failure) -> {
                                      if (failure != null) error(failure);
                                      else
                                        manager.addFile(
                                            node,
                                            commit -> {
                                              error(commit);
                                              if (commit == null) toast(t("done"));
                                            });
                                    });
                          });
                    })
                .setNegativeButton(t("cancel"), null)
                .show());
  }

  private void configureDigest() {
    JSONObject templates = CenterIndex.settings().optJSONObject("templates");
    List<String> names = new ArrayList<>();
    if (templates != null) {
      Iterator<String> keys = templates.keys();
      while (keys.hasNext()) names.add(keys.next());
    }
    JSONObject saved = templates;
    choices(
        "templates",
        names.toArray(new String[0]),
        i ->
            choices(
                "schedule",
                new String[] {t("daily"), t("weekly")},
                frequency ->
                    input(
                        "digestTime",
                        "09:00",
                        time -> {
                          try {
                            String[] parts = time.split(":");
                            int hour = Integer.parseInt(parts[0]),
                                minute = Integer.parseInt(parts[1]);
                            if (hour < 0 || hour > 23 || minute < 0 || minute > 59)
                              throw new Exception();
                            JSONObject template = saved.getJSONObject(names.get(i));
                            dialogBuilder()
                                .setMessage(t("scheduleConsent"))
                                .setPositiveButton(
                                    t("confirm"),
                                    (d, w) -> {
                                      try {
                                        CenterIndex.setting("consent", true);
                                        CenterDigests.add(
                                            names.get(i),
                                            template.optString("query"),
                                            CenterData.Scope.parse(template.getJSONObject("scope")),
                                            frequency == 0 ? 1 : 7,
                                            hour,
                                            minute);
                                        toast(t("done"));
                                      } catch (Exception e) {
                                        error(e.getMessage());
                                      }
                                    })
                                .setNegativeButton(t("cancel"), null)
                                .show();
                          } catch (Exception e) {
                            error(t("digestTime"));
                          }
                        })));
  }

  private void digestList() {
    clearBody();
    heading("schedule");
    button("addSchedule", this::configureDigest);
    JSONObject schedules = CenterIndex.settings().optJSONObject("schedules");
    if (schedules == null) return;
    Iterator<String> keys = schedules.keys();
    while (keys.hasNext()) {
      JSONObject job = schedules.optJSONObject(keys.next());
      if (job == null || job.optBoolean("deleted")) continue;
      row(
          job.optString("name")
              + " · "
              + new Date(job.optLong("next"))
              + " · "
              + job.optString("error"),
          () ->
              choices(
                  "schedule",
                  new String[] {t("pause"), t("resume"), t("delete")},
                  i -> {
                    try {
                      job.put("enabled", i == 1);
                      if (i == 1) job.put("next", System.currentTimeMillis() + 1000);
                      if (i == 2) job.put("deleted", true);
                      CenterDigests.update(job);
                      CenterDigests.schedule();
                      digestList();
                    } catch (Exception e) {
                      error(e.getMessage());
                    }
                  }));
    }
  }

  private void chooseRole(String role, String title) {
    List<TPAiStore.Connection> connections = TPAiStore.list();
    String[] names = new String[connections.size() + 1];
    names[0] = t("defaultModel");
    for (int i = 0; i < connections.size(); i++)
      names[i + 1] = connections.get(i).title() + " · " + connections.get(i).model;
    choices(
        title,
        names,
        i -> {
          if (i == 0) {
            CenterIndex.setting(role, "");
            CenterIndex.setting(role + "Name", "");
            return;
          }
          TPAiStore.Connection connection = connections.get(i - 1);
          CenterIndex.setting(role, connection.id);
          input(title, connection.model, model -> CenterIndex.setting(role + "Name", model));
        });
  }

  private void clearHistorySource() {
    List<CenterData.Source> sources = new ArrayList<>(index.sources);
    String[] names = new String[sources.size()];
    for (int i = 0; i < names.length; i++)
      names[i] = sources.get(i).title + " · " + sources.get(i).accountName;
    choices(
        "sources",
        names,
        i -> {
          CenterData.Source source = sources.get(i);
          dialogBuilder()
              .setMessage(t("clearHistorySource"))
              .setPositiveButton(
                  t("confirm"),
                  (d, w) ->
                      CenterStore.IO.execute(
                          () -> {
                            try {
                              for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++)
                                if (UserConfig.getInstance(a).isClientActivated()) {
                                  long owner = UserConfig.getInstance(a).getClientUserId();
                                  for (JSONObject thread :
                                      CenterStore.get().list(a, owner, "thread")) {
                                    JSONArray claims = thread.optJSONArray("claims");
                                    boolean match = false;
                                    JSONArray sourceKeys = thread.optJSONArray("sourceKeys");
                                    for (int n = 0;
                                        sourceKeys != null && n < sourceKeys.length();
                                        n++)
                                      if (source.key().equals(sourceKeys.optString(n)))
                                        match = true;
                                    for (int c = 0; claims != null && c < claims.length(); c++) {
                                      JSONArray evidence =
                                          claims.optJSONObject(c).optJSONArray("evidence");
                                      for (int e = 0;
                                          evidence != null && e < evidence.length();
                                          e++) {
                                        JSONObject item = evidence.optJSONObject(e);
                                        if (item.optInt("account") == source.account
                                            && item.optLong("chat") == source.dialog) match = true;
                                      }
                                    }
                                    if (match)
                                      CenterStore.get()
                                          .remove(a, owner, "thread", thread.optString("id"));
                                  }
                                }
                              AndroidUtilities.runOnUIThread(() -> toast(t("done")));
                            } catch (Exception e) {
                              FileLog.e(e);
                            }
                          }))
              .setNegativeButton(t("cancel"), null)
              .show();
        });
  }

  private void clearCenterData() {
    if (sync != null) sync.cancel();
    aiCancelled.set(true);
    CenterTaskService.stopWork();
    CenterDigests.cancelWork();
    report = null;
    drilldown = null;
    CenterThumbnails.clear();
    CenterIndex.setting("schedules", new JSONObject());
    CenterIndex.setting("templates", new JSONObject());
    CenterIndex.setting("sets", new JSONObject());
    CenterDigests.schedule();
    index.clear(
        e -> {
          error(e);
          show(8);
        });
  }

  private void settings() {
    JSONObject o = CenterIndex.settings();
    long sourceCount = index.sources.stream().filter(index.scope::requests).count();
    body.addView(ui.section(tr("What is analysed", "Что анализируется")));
    row(tr("Sources", "Источники") + " · " + sourceCount + " / " + index.sources.size()
        + "\n" + tr("Choose groups and channels of each account", "Выберите группы и каналы каждого аккаунта"),
        () -> show(0));
    row(tr("Accounts and filters", "Аккаунты и фильтры") + "\n"
        + tr("Which accounts take part, search and filter sources", "Какие аккаунты участвуют, поиск и фильтры по источникам"),
        () -> show(9));
    body.addView(ui.section(tr("Speech recognition", "Распознавание речи")));
    button(
        "asrModel",
        () ->
            input(
                "asrModel",
                CenterIndex.settings().optString("asrModel", "whisper-1"),
                name -> CenterIndex.setting("asrModel", name)));
    button("cloudAsr", () -> chooseRole("speechModel", "cloudAsr"));
    toggle(
        "speakerLabels",
        o.optBoolean("asrDiarization", false),
        enabled -> CenterIndex.setting("asrDiarization", enabled));
    info(t("speakerScopeHint"));
    body.addView(ui.section(tr("AI models", "Модели ИИ")));
    button("fastModel", () -> chooseRole("fastModel", "fastModel"));
    button("reportModel", () -> chooseRole("reportModel", "reportModel"));
    button("visionModel", () -> chooseRole("visionModel", "visionModel"));
    button("aiConnections", () -> presentFragment(new TelePremiumAiActivity()));
    body.addView(ui.section(tr("Usage and limits", "Расход и лимиты")));
    info(t("usage") + "\n" + CenterUsage.summary());
    for (String budget : new String[] {"dailyLimit", "monthlyLimit"})
      button(
          budget,
          () ->
              input(
                  budget,
                  "" + o.optLong(budget, budget.equals("dailyLimit") ? 1000000 : 10000000),
                  v -> {
                    try {
                      CenterIndex.setting(budget, Math.max(1, Long.parseLong(v)));
                    } catch (Exception e) {
                      error(e.getMessage());
                    }
                  }));
    body.addView(ui.section(tr("Background work", "Фоновая работа")));
    button("schedule", this::digestList);
    toggle(
        "wifi",
        o.optBoolean("wifi", true),
        v -> {
          CenterIndex.setting("wifi", v);
          CenterTaskService.schedule();
        });
    toggle(
        "charging",
        o.optBoolean("charging", false),
        v -> {
          CenterIndex.setting("charging", v);
          CenterTaskService.schedule();
        });
    button(
        "maxVideo",
        () ->
            input(
                "maxVideo",
                "" + o.optInt("maxVideo", 40),
                v -> {
                  try {
                    CenterIndex.setting("maxVideo", Math.max(1, Integer.parseInt(v)));
                  } catch (Exception e) {
                    error(e.getMessage());
                  }
                }));
    body.addView(ui.section(tr("Data", "Данные")));
    button("clearHistorySource", this::clearHistorySource);
    toggle(
        "removeDeleted",
        o.optBoolean("removeDeleted", false),
        v -> CenterIndex.setting("removeDeleted", v));
    button(
        "cacheClear",
        () -> {
          CenterThumbnails.clear();
          CenterStore.IO.execute(
              () -> {
                try {
                  for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++)
                    if (UserConfig.getInstance(a).isClientActivated()) {
                      long owner = UserConfig.getInstance(a).getClientUserId();
                      CenterStore.get().removeKind(a, owner, "preview");
                    }
                } catch (Exception e) {
                  FileLog.e(e);
                }
              });
        });
    for (String[] pair :
        new String[][] {
          {"retention", "retentionDays"}, {"size", "indexMb"}, {"budget", "messageBudget"}
        })
      button(
          pair[0],
          () ->
              input(
                  pair[0],
                  ""
                      + o.optInt(
                          pair[1],
                          pair[1].equals("retentionDays")
                              ? 90
                              : pair[1].equals("indexMb") ? 256 : 20000),
                  v -> {
                    try {
                      CenterIndex.setting(pair[1], Math.max(1, Integer.parseInt(v)));
                    } catch (Exception e) {
                      error(e.getMessage());
                    }
                  }));
    info(t("indexSize") + ": " + CenterStore.get().bytes());
    body.addView(ui.section(tr("Protection", "Защита")));
    toggle("local", o.optBoolean("localOnly", false), b -> CenterIndex.setting("localOnly", b));
    toggle(
        "secure",
        o.optBoolean("secure", true),
        b -> {
          CenterIndex.setting("secure", b);
          applySecure();
        });
    button(
        "security",
        () ->
            choices(
                "security",
                new String[] {t("pin"), t("password"), t("pattern"), t("disabled")},
                which -> {
                  if (which == 3) {
                    TPAiStore.putPrivate("center_lock", "");
                    lockFlag(false);
                    VaultFolderBiometric.forget(getParentActivity(), "aicenter");
                    return;
                  }
                  secretKind = new String[] {"PIN", "PASSWORD", "PATTERN"}[which];
                  input(
                      which == 0 ? "pin" : which == 1 ? "password" : "pattern",
                      "",
                      raw -> {
                        CenterStore.IO.execute(
                            () -> {
                              String failure = null;
                              try {
                                VaultSecretPolicy.Kind kind =
                                    VaultSecretPolicy.Kind.valueOf(secretKind);
                                if (!VaultSecretPolicy.isAcceptable(
                                    VaultSecretPolicy.check(kind, raw)))
                                  throw new Exception(t("wrong"));
                                byte[] master = VaultFolderLock.newFolderKey(),
                                    fk = VaultFolderLock.newFolderKey();
                                char[] secret = VaultSecretPolicy.toSecret(kind, raw);
                                String record =
                                    VaultFolderLock.seal(secret, "aicenter", master, fk).encode();
                                TPAiStore.putPrivate(
                                    "center_lock_master",
                                    android.util.Base64.encodeToString(master, 2));
                                TPAiStore.putPrivate("center_lock_kind", secretKind);
                                TPAiStore.putPrivate("center_lock", record);
                                lockFlag(true);
                                TPAiStore.putPrivate(
                                    "center_bio_setup", android.util.Base64.encodeToString(fk, 2));
                                Arrays.fill(master, (byte) 0);
                                Arrays.fill(fk, (byte) 0);
                                Arrays.fill(secret, '\0');
                              } catch (Exception e) {
                                failure = e.getMessage();
                              }
                              String f = failure;
                              AndroidUtilities.runOnUIThread(
                                  () -> {
                                    error(f);
                                    if (f == null) toast(t("done"));
                                  });
                            });
                      });
                }));
    button(
        "biometric",
        () -> {
          String k = TPAiStore.getPrivate("center_bio_setup");
          if (k.isEmpty()) {
            toast(t("biometricRequired"));
            return;
          }
          byte[] key = android.util.Base64.decode(k, 2);
          VaultFolderBiometric.enroll(
              getParentActivity(),
              "aicenter",
              key,
              t("biometric"),
              t("cancel"),
              e -> {
                error(e);
                if (e == null) TPAiStore.putPrivate("center_bio_setup", "");
              });
          Arrays.fill(key, (byte) 0);
        });
    body.addView(ui.section(tr("Danger zone", "Опасная зона")));
    button(
        "clear",
        () ->
            dialogBuilder()
                .setMessage(t("confirmClear"))
                .setPositiveButton(t("confirm"), (d, w) -> clearCenterData())
                .setNegativeButton(t("cancel"), null)
                .show());
  }
}
