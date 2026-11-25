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
      if (body != null) body.removeAllViews();
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
  private TextView scopeLine, status;
  private ScrollView scroll;
  private int screen;
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
    a.screen = Math.max(0, Math.min(8, screen));
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

  private TextView label(String text, int size) {
    TextView v = new TextView(getParentActivity());
    v.setText(text);
    v.setTextSize(size);
    v.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
    v.setPadding(dp(12), dp(10), dp(12), dp(10));
    return v;
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
    TextView v = label(t(key), 19);
    v.setTypeface(AndroidUtilities.bold());
    body.addView(v);
  }

  private TextView row(String text, Runnable action) {
    TextView v = label(text, 15);
    v.setMinHeight(dp(48));
    v.setBackground(Theme.getSelectorDrawable(false));
    body.addView(v, new LinearLayout.LayoutParams(-1, -2));
    if (action != null) v.setOnClickListener(w -> action.run());
    return v;
  }

  private void button(String key, Runnable action) {
    row(t(key), action);
  }

  private void info(String text) {
    row(text, null);
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
    CheckBox c = new CheckBox(getParentActivity());
    c.setText(t(key));
    c.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
    c.setChecked(checked);
    c.setPadding(dp(12), dp(8), dp(12), dp(8));
    c.setOnCheckedChangeListener((v, b) -> change.accept(b));
    body.addView(c);
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
            if (id == -1) finishFragment();
          }
        });
    root = new LinearLayout(context);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
    scopeLine = label(t("sources"), 14);
    scopeLine.setOnClickListener(
        v -> {
          drilldown = null;
          authorReport = false;
          show(0);
        });
    root.addView(scopeLine);
    status = label("", 12);
    root.addView(status);
    scroll = new ScrollView(context);
    body = new LinearLayout(context);
    body.setOrientation(LinearLayout.VERTICAL);
    scroll.addView(body);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    HorizontalScrollView bottom = new HorizontalScrollView(context);
    bottom.setHorizontalScrollBarEnabled(false);
    navigation = new LinearLayout(context);
    bottom.addView(navigation);
    root.addView(bottom, new LinearLayout.LayoutParams(-1, dp(56)));
    for (int i = 0; i < SCREENS.length; i++) {
      final int tab = i;
      TextView v = label(t(SCREENS[i]), 13);
      v.setGravity(Gravity.CENTER);
      v.setOnClickListener(w -> show(tab));
      navigation.addView(v, new LinearLayout.LayoutParams(-2, -1));
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
      if (body != null) body.removeAllViews();
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

  private void unlock() {
    String record = TPAiStore.getPrivate("center_lock");
    if (record.isEmpty() && lockPresent()) {
      body.removeAllViews();
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
    body.removeAllViews();
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

  private void show(int tab) {
    if (!unlocked) return;
    int oldScreen = screen;
    screen = tab;
    if (tab == 7 && oldScreen != 7) archiveDetail = false;
    uiVersion++;
    body.removeAllViews();
    scopeLine.setText(
        t("sources")
            + " · "
            + index.sources.stream().filter(index.scope::allows).count()
            + " / "
            + index.scope.whole.size()
            + " · "
            + index.selected().size());
    heading(SCREENS[tab]);
    switch (tab) {
      case 0:
        sources();
        break;
      case 1:
        summaries(false);
        break;
      case 2:
        statistics(false);
        break;
      case 3:
        statistics(true);
        break;
      case 4:
        media(false);
        break;
      case 5:
        media(true);
        break;
      case 6:
        summaries(true);
        break;
      case 7:
        reportArchive();
        break;
      case 8:
        settings();
        break;
      default:
        info(t("empty"));
    }
  }

  private void sources() {
    button(
        "refresh",
        () -> {
          if (sync != null) sync.cancel();
          status.setText(t("loading"));
          sync =
              new CenterSync(
                  index,
                  e -> {
                    error(e);
                    status.setText("");
                    show(0);
                  });
          sync.discover();
        });
    button(
        "load",
        () -> {
          index.saveScope();
          if (sync != null) sync.cancel();
          status.setText(t("loading"));
          sync =
              new CenterSync(
                  index,
                  e -> {
                    error(e);
                    status.setText("");
                    show(0);
                  });
          sync.load();
        });
    button(
        "cancel",
        () -> {
          if (sync != null) sync.cancel();
          status.setText("");
          reload();
        });
    button(
        "period",
        () ->
            choices(
                "period",
                new String[] {"1", "7", "30", "90", t("customDates"), t("sinceRead")},
                i -> {
                  index.scope.sinceRead = i == 5;
                  if (i < 4) {
                    index.scope.to = System.currentTimeMillis() / 1000;
                    index.scope.from = index.scope.to - new int[] {1, 7, 30, 90}[i] * 86400L;
                    index.saveScope();
                    show(0);
                  } else if (i == 4)
                    input(
                        "customDates",
                        "2026-11-01 / 2026-11-24",
                        dates -> {
                          try {
                            String[] v = dates.split("/");
                            java.text.SimpleDateFormat f =
                                new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
                            f.setLenient(false);
                            index.scope.from = f.parse(v[0].trim()).getTime() / 1000;
                            index.scope.to = f.parse(v[1].trim()).getTime() / 1000 + 86399;
                            if (index.scope.to < index.scope.from) throw new Exception();
                            index.saveScope();
                            show(0);
                          } catch (Exception e) {
                            error(t("customDates"));
                          }
                        });
                  else {
                    index.scope.to = System.currentTimeMillis() / 1000;
                    index.scope.from = reviewFrom(index.scope);
                    index.saveScope();
                    show(0);
                  }
                }));
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
                .setNegativeButton(t("cancel"), null)
                .show();
          else {
            index.scope.includePrivate = false;
            index.saveScope();
            show(0);
          }
        });
    for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++)
      if (UserConfig.getInstance(a).isClientActivated()) {
        final int account = a;
        long n = index.sources.stream().filter(s -> s.account == account).count();
        String mode =
            index.scope.excluded.contains(a)
                ? t("excluded")
                : index.scope.whole.contains(a) ? t("all") : t("selected");
        LinearLayout accountIdentity = new LinearLayout(getParentActivity());
        avatar(accountIdentity, a, UserConfig.getInstance(a).getClientUserId());
        long loaded = index.messages.stream().filter(m -> m.account == account).count();
        TextView session =
            label(t("messages") + ": " + loaded + " · " + t("session") + ": " + t("available"), 14);
        accountIdentity.addView(session);
        body.addView(accountIdentity);
        row(
            UserObject.getUserName(UserConfig.getInstance(a).getCurrentUser())
                + " · "
                + n
                + " · "
                + mode,
            () ->
                choices(
                    "account",
                    new String[] {t("all"), t("selected"), t("excluded")},
                    i -> {
                      index.scope.excluded.remove(account);
                      index.scope.whole.remove(account);
                      if (i == 0) index.scope.whole.add(account);
                      if (i == 2) {
                        aiCancelled.set(true);
                        report = null;
                        index.scope.excluded.add(account);
                        index.messages.removeIf(m -> m.account == account);
                        CenterIndex.logout(account);
                      }
                      index.saveScope();
                      show(0);
                    }));
      }
    button(
        "account",
        () -> {
          java.util.ArrayList<Integer> accounts = new java.util.ArrayList<>();
          java.util.ArrayList<String> names = new java.util.ArrayList<>();
          for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++)
            if (UserConfig.getInstance(a).isClientActivated()) {
              accounts.add(a);
              names.add(UserObject.getUserName(UserConfig.getInstance(a).getCurrentUser()));
            }
          boolean[] chosen = new boolean[accounts.size()];
          for (int i = 0; i < chosen.length; i++)
            chosen[i] = filter.accounts.isEmpty() || filter.accounts.contains(accounts.get(i));
          dialogBuilder()
              .setTitle(t("account"))
              .setMultiChoiceItems(
                  names.toArray(new String[0]), chosen, (d, i, on) -> chosen[i] = on)
              .setPositiveButton(
                  t("confirm"),
                  (d, w) -> {
                    filter.accounts.clear();
                    for (int i = 0; i < chosen.length; i++)
                      if (chosen[i]) filter.accounts.add(accounts.get(i));
                    show(0);
                  })
              .show();
        });
    button(
        "search",
        () ->
            input(
                "search",
                filter.text,
                v -> {
                  filter.text = v;
                  show(0);
                }));
    String[] types = {"", "group", "supergroup", "channel", "topic", "private"};
    button(
        "type",
        () ->
            choices(
                "type",
                new String[] {
                  t("all"), t("group"), t("supergroup"), t("channel"), t("topic"), t("privateChats")
                },
                i -> {
                  filter.type = types[i];
                  show(0);
                }));
    String[] roles = {"", "owner", "admin", "member"};
    button(
        "role",
        () ->
            choices(
                "role",
                new String[] {t("all"), t("owner"), t("admin"), t("member")},
                i -> {
                  filter.role = roles[i];
                  show(0);
                }));
    button(
        "folder",
        () ->
            input(
                "folder",
                filter.folder,
                v -> {
                  filter.folder = v;
                  show(0);
                }));
    button(
        "status",
        () ->
            choices(
                "status",
                new String[] {t("all"), t("pinned"), t("archived"), t("muted")},
                i -> {
                  filter.status = new String[] {"", "pinned", "archive", "muted"}[i];
                  show(0);
                }));
    button(
        "active",
        () ->
            choices(
                "active",
                new String[] {t("all"), t("active"), t("quiet")},
                i -> {
                  filter.activity = new String[] {"", "active", "quiet"}[i];
                  show(0);
                }));
    button(
        "minActivity",
        () ->
            input(
                "minActivity",
                "" + filter.minPerDay,
                v -> {
                  try {
                    filter.minPerDay = Math.max(0, Integer.parseInt(v));
                    show(0);
                  } catch (Exception e) {
                    error(e.getMessage());
                  }
                }));
    List<CenterData.Source> matching = new ArrayList<>();
    for (CenterData.Source s : index.sources)
      if (filter.matches(s, index.messages, index.scope.from, index.scope.to)) matching.add(s);
    button(
        "selectAll",
        () -> {
          for (CenterData.Source s : matching) {
            index.scope.excluded.remove(s.account);
            index.scope.sources.add(s.key());
          }
          index.saveScope();
          show(0);
        });
    button(
        "excludeMatching",
        () -> {
          for (CenterData.Source s : matching) {
            if (index.scope.whole.remove(s.account))
              for (CenterData.Source q : index.sources)
                if (q.account == s.account) index.scope.sources.add(q.key());
            index.scope.sources.remove(s.key());
          }
          index.saveScope();
          show(0);
        });
    button(
        "saveSet",
        () ->
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
                }));
    button(
        "sets",
        () -> {
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
        });
    info(t("count") + ": " + matching.size() + " / " + index.selected().size());
    for (CenterData.Source s : matching) {
      CheckBox v = new CheckBox(getParentActivity());
      v.setText(
          s.title
              + " · "
              + s.accountName
              + " · "
              + t(s.type)
              + " · "
              + t(s.role)
              + "\n"
              + t("state")
              + ": "
              + (s.available ? "✓" : "—")
              + " · "
              + (s.loadedAt > 0 ? new Date(s.loadedAt).toString() : "—"));
      v.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
      v.setChecked(index.scope.allows(s));
      v.setOnCheckedChangeListener(
          (b, on) -> {
            if (index.scope.whole.remove(s.account)) {
              for (CenterData.Source q : index.sources)
                if (q.account == s.account) index.scope.sources.add(q.key());
            }
            index.scope.excluded.remove(s.account);
            if (on) index.scope.sources.add(s.key());
            else index.scope.sources.remove(s.key());
            index.saveScope();
            scopeLine.setText(t("count") + ": " + index.selected().size());
          });
      body.addView(v);
    }
  }

  private void summaries(boolean chat) {
    if (!chat)
      button(
          "type",
          () ->
              choices(
                  "type",
                  new String[] {t("groupReport"), t("authorsReport"), t("new"), t("compare")},
                  i -> {
                    reportKind = i;
                  }));
    EditText composer = new EditText(getParentActivity());
    composer.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
    composer.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText));
    composer.setBackgroundTintList(
        android.content.res.ColorStateList.valueOf(
            getThemedColor(Theme.key_windowBackgroundWhiteBlueText)));
    composer.setHint(t("query"));
    composer.setMaxLines(4);
    composer.setText(query);
    body.addView(composer);
    button(
        "responseMode",
        () ->
            choices(
                "responseMode",
                new String[] {
                  t("shortText"), t("detailedText"), t("shortIllustrated"), t("detailedIllustrated")
                },
                mode -> {
                  detailed = mode % 2 == 1;
                  CenterExport.Options options = exportOptions();
                  options.images = mode >= 2;
                  saveExportOptions(options);
                }));
    toggle(
        "personal",
        index.scope.includePersonal,
        b -> {
          index.scope.includePersonal = b;
          index.saveScope();
        });
    button(
        "ask",
        () -> {
          query = composer.getText().toString().trim();
          if (query.isEmpty()) {
            toast(t("query"));
            return;
          }
          runReport(query);
        });
    button("cancel", () -> aiCancelled.set(true));
    button(
        "templates",
        () -> {
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
                  JSONObject v = saved.getJSONObject(names.get(i));
                  query = v.optString("query");
                  index.scope = CenterData.Scope.parse(v.getJSONObject("scope"));
                  index.saveScope();
                  show(screen);
                } catch (Exception e) {
                  error(e.getMessage());
                }
              });
        });
    if (report != null) {
      renderReport(report);
      button(
          "saveTemplate",
          () ->
              input(
                  "name",
                  "",
                  name -> {
                    try {
                      JSONObject ts = CenterIndex.settings().optJSONObject("templates");
                      if (ts == null) ts = new JSONObject();
                      ts.put(
                          name,
                          new JSONObject().put("query", query).put("scope", index.scope.json()));
                      CenterIndex.setting("templates", ts);
                    } catch (Exception e) {
                      error(e.getMessage());
                    }
                  }));
      button("archive", () -> persistReport(report, "report"));
    }
    if (chat) button("reports", this::history);
    button(
        "markReviewed",
        () -> {
          long now = System.currentTimeMillis();
          for (CenterData.Source source : index.sources)
            if (index.scope.allows(source)) {
              source.readAt = now;
              CenterStore.IO.execute(
                  () -> {
                    try {
                      CenterStore.get()
                          .put(source.account, source.owner, "source", source.key(), source.json());
                    } catch (Exception e) {
                      FileLog.e(e);
                    }
                  });
            }
          toast(t("done"));
        });
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
              drilldown == null ? index.selected() : new ArrayList<>(drilldown);
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

  private void renderReport(CenterReports.Report r) {
    button("blocks", this::reportBuilder);
    button("export", () -> exportReport(false, false));
    button("preview", () -> exportReport(false, true));
    button("saveCloud", () -> exportReport(true, false));
    info(
        r.question
            + "\n"
            + new Date(r.created)
            + "\n"
            + new Date(r.from * 1000)
            + " — "
            + new Date(r.to * 1000));
    info(
        t("coverage")
            + ": "
            + r.analyzed
            + " / "
            + r.indexed
            + " ("
            + (r.indexed == 0 ? 0 : (100L * r.analyzed / r.indexed))
            + "%) · "
            + (r.complete ? t("done") : t("partial")));
    info(t("coverageIndexed"));
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
        for (int i = 0; authors != null && i < Math.min(40, authors.length()); i++) {
          JSONObject author = authors.optJSONObject(i);
          row(
              author.optString("name") + " · " + author.optInt("count"),
              () -> {
                List<CenterData.Message> hits = new ArrayList<>();
                for (CenterData.Message m : index.selected())
                  if (m.author == author.optLong("id")) hits.add(m);
                messageDetails(hits);
              });
        }
      } else if (block.equals("charts") && r.statistics != null) {
        heading("activity");
        int version = uiVersion;
        for (int mode = 0; mode < 5; mode++) {
          final int kind = mode;
          TextView title =
              row(
                  t(new String[] {"rank", "days", "heatmap", "content", "concentration"}[mode]),
                  null);
          ImageView chart = new ImageView(getParentActivity());
          chart.setScaleType(ImageView.ScaleType.FIT_CENTER);
          body.addView(chart, new LinearLayout.LayoutParams(-1, dp(240)));
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
    ImageView image = new ImageView(getParentActivity());
    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
    container.addView(image, new LinearLayout.LayoutParams(-1, dp(170)));
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
    container.addView(
        label(
            m.authorName
                + " · "
                + m.sourceTitle
                + " · "
                + new Date(m.date * 1000L)
                + "\n"
                + m.description,
            13));
  }

  private void renderMain(CenterReports.Report r) {
    for (CenterReports.Claim claim :
        r.claims.subList(
            Math.min(reportPage * 50, r.claims.size()),
            Math.min((reportPage + 1) * 50, r.claims.size()))) {
      TextView title = row(claim.title, null);
      title.setTypeface(AndroidUtilities.bold());
      if (!claim.topic.isEmpty()) info(t("topics") + ": " + claim.topic);
      info(claim.text);
      info(t(claim.verified() ? claim.certainty : "unverified"));
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
                TextView v =
                    label(
                        m.authorName
                            + " · "
                            + m.sourceTitle
                            + " · "
                            + m.accountName
                            + "\n"
                            + new Date(m.date * 1000L)
                            + "\n“"
                            + ev.quote
                            + "”"
                            + (m.deleted ? "\n" + t("deleted") : ""),
                        14);
                v.setOnClickListener(w -> openEvidence(m));
                v.setOnLongClickListener(
                    w -> {
                      evidenceActions(m, claim);
                      return true;
                    });
                LinearLayout identity = new LinearLayout(getParentActivity());
                identity.setGravity(Gravity.CENTER_VERTICAL);
                avatar(identity, m.account, m.author > 0 ? m.author : m.dialog);
                identity.addView(v, new LinearLayout.LayoutParams(0, -2, 1));
                evidence.addView(identity);
                if (ev.timeMs > 0) {
                  TextView time = label(CenterTranscript.stamp(ev.timeMs), 14);
                  time.setOnClickListener(w -> playAt(m, ev.timeMs));
                  evidence.addView(time);
                }
                if (ev.page > 0) {
                  TextView pageLabel = label(t("page") + " " + ev.page, 14);
                  pageLabel.setOnClickListener(w -> mediaDetails(m, ev.page));
                  evidence.addView(pageLabel);
                }
                if (exportOptions().images) reportImage(r, ev, evidence);
              }

            if (opening) expandedEvidence.add(evidenceKey);
            else expandedEvidence.remove(evidenceKey);
          };
      row(t("evidence") + " (" + claim.evidence.size() + ")", toggleEvidence);
      body.addView(evidence);
      if (expandedEvidence.contains(evidenceKey)) toggleEvidence.run();
      row(
          t("expand") + " · " + claim.title,
          () -> {
            drilldown = new ArrayList<>();
            for (CenterReports.Evidence ev : claim.evidence) drilldown.add(ev.message);
            query = t("expand") + ": " + claim.title;
            show(6);
          });
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
                body.removeAllViews();
                heading(kind.equals("thread") ? "analyst" : "reports");
                for (JSONObject o : values) {
                  CenterReports.Report r = CenterReports.restore(o, index.readable());
                  row(
                      r.question + " · " + new Date(r.created),
                      () -> {
                        report = r;
                        query = r.question;
                        reportPage = 0;
                        if (kind.equals("report")) {
                          archiveDetail = true;
                          show(7);
                        } else show(6);
                      });
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

  private List<CenterData.Message> mediaMessages(boolean speech) {
    List<CenterData.Message> out = new ArrayList<>();
    for (CenterData.Message m : index.selected())
      if (!m.protectedMedia
          && !m.kind.equals("text")
          && (!speech || Arrays.asList("video", "audio", "voice", "round").contains(m.kind))
          && (mediaKind.isEmpty() || mediaKind.equals(m.kind))
          && (mediaCategory.isEmpty() || mediaCategory.equals(CenterTopics.category(m)))
          && (semanticHits == null || semanticHits.contains(m.qualified()))
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

  private void media(boolean speech) {
    if (!speech) {
      button(
          "semanticSearch",
          () ->
              input(
                  "query",
                  "",
                  question -> {
                    semanticHits = null;
                    String previous = mediaSearch;
                    mediaSearch = "";
                    List<CenterData.Message> data = mediaMessages(false);
                    mediaSearch = previous;
                    dialogBuilder()
                        .setMessage(
                            t("consent")
                                + "\n"
                                + t("estimate")
                                + ": "
                                + data.size()
                                + " · "
                                + t("indexedDescriptions"))
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
                  }));
      button(
          "mediaType",
          () ->
              choices(
                  "mediaType",
                  new String[] {t("all"), t("chartImage"), t("receiptImage"), t("photoImage")},
                  i -> {
                    mediaCategory =
                        new String[] {"", "chartImage", "receiptImage", "photoImage"}[i];
                    mediaPage = 0;
                    show(screen);
                  }));
    }
    button(
        "search",
        () ->
            input(
                "search",
                mediaSearch,
                v -> {
                  mediaSearch = v;
                  semanticHits = null;
                  mediaPage = 0;
                  show(screen);
                }));
    button(
        "type",
        () ->
            choices(
                "type",
                new String[] {
                  t("all"), t("photo"), t("video"), t("audio"), t("voice"), t("pdf"), t("document")
                },
                i -> {
                  mediaKind =
                      new String[] {"", "photo", "video", "audio", "voice", "pdf", "document"}[i];
                  mediaPage = 0;
                  show(screen);
                }));
    button(
        "authors",
        () -> {
          Map<Long, String> map = new LinkedHashMap<>();
          for (CenterData.Message m : index.selected()) map.put(m.author, m.authorName);
          List<Long> ids = new ArrayList<>(map.keySet());
          String[] names = new String[ids.size() + 1];
          names[0] = t("all");
          for (int i = 0; i < ids.size(); i++) names[i + 1] = map.get(ids.get(i));
          choices(
              "authors",
              names,
              i -> {
                index.scope.authors.clear();
                if (i > 0) index.scope.authors.add(ids.get(i - 1));
                index.saveScope();
                show(screen);
              });
        });
    button(
        "file",
        () -> {
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
        });
    button("cloud", this::pickCloud);
    if (speech) {
      info(t("offlineSpeech"));
      info(t("speakerScopeHint"));
      button(
          "language",
          () ->
              input(
                  "language",
                  CenterIndex.settings().optString("language", "auto"),
                  v -> CenterIndex.setting("language", v)));
    }
    List<CenterData.Message> list = mediaMessages(speech);
    button(
        "selectAll",
        () -> {
          for (CenterData.Message m : list) pickedMedia.add(m.qualified());
          show(screen);
        });
    button(
        "cancel",
        () -> {
          pickedMedia.clear();
          show(screen);
        });
    if (speech) {
      button("transcribe", () -> queueMedia(list, "transcribe"));
      button(
          "cloudAsr",
          () ->
              dialogBuilder()
                  .setMessage(t("cloudAsrConsent"))
                  .setPositiveButton(
                      t("confirm"),
                      (d, w) -> {
                        CenterIndex.setting("consent", true);
                        queueMedia(list, "cloudAsr");
                      })
                  .setNegativeButton(t("cancel"), null)
                  .show());
    } else {
      button("download", () -> queueMedia(list, "download"));
      button("ocr", () -> queueMedia(list, "ocr"));
      button("describe", () -> queueMedia(list, "describe"));
      button("transcriber", () -> show(5));
    }
    button("queue", this::taskQueue);
    button("refresh", this::reload);
    info(t("media") + ": " + list.size() + " · " + pickedMedia.size());
    int version = uiVersion;
    for (int i = mediaPage * 40; i < Math.min(list.size(), (mediaPage + 1) * 40); i++) {
      CenterData.Message m = list.get(i);
      LinearLayout card = new LinearLayout(getParentActivity());
      card.setOrientation(LinearLayout.VERTICAL);
      body.addView(card);
      if (!m.frameTimes.isEmpty() || m.pages > 0) {
        android.widget.ImageView preview = new android.widget.ImageView(getParentActivity());
        preview.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        card.addView(preview, new LinearLayout.LayoutParams(-1, dp(160)));
        CenterThumbnails.load(
            m,
            m.frameTimes.isEmpty() ? 0 : m.frameTimes.get(0),
            m.pages > 0 ? 1 : 0,
            b -> {
              if (version == uiVersion && b != null) preview.setImageBitmap(b);
            });
        preview.setOnClickListener(w -> mediaDetails(m, 0));
      }
      CheckBox choice = new CheckBox(getParentActivity());
      choice.setText(
          m.authorName
              + " · "
              + m.sourceTitle
              + " · "
              + m.accountName
              + "\n"
              + new Date(m.date * 1000L)
              + " · "
              + t(m.kind.equals("link") ? "links" : m.kind)
              + "\n"
              + (m.text.length() > 160 ? m.text.substring(0, 160) + "…" : m.text));
      choice.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
      choice.setChecked(pickedMedia.contains(m.qualified()));
      choice.setOnCheckedChangeListener(
          (v, on) -> {
            if (on) pickedMedia.add(m.qualified());
            else pickedMedia.remove(m.qualified());
          });
      card.addView(choice);
      TextView details = label(t("details"), 14);
      card.addView(details);
      details.setOnClickListener(w -> mediaDetails(m, 0));
      if (!m.transcript.isEmpty())
        for (CenterTranscript.Segment segment :
            CenterTranscript.parse(m.transcript, m.duration)
                .subList(
                    0, Math.min(12, CenterTranscript.parse(m.transcript, m.duration).size()))) {
          TextView v = label(transcriptLine(segment), 14);
          v.setOnClickListener(w -> playAt(m, segment.start));
          card.addView(v);
        }
      if (!m.hash.isEmpty()) {
        List<String> copies = new ArrayList<>();
        try {
          long hash = Long.parseUnsignedLong(m.hash, 16);
          for (CenterData.Message q : list)
            if (q != m
                && !q.hash.isEmpty()
                && Long.bitCount(hash ^ Long.parseUnsignedLong(q.hash, 16)) <= 6)
              copies.add(q.sourceTitle + " · " + q.authorName + " · " + new Date(q.date * 1000L));
        } catch (Exception ignored) {
        }
        if (!copies.isEmpty()) {
          TextView v = label(t("copies") + "\n" + android.text.TextUtils.join("\n", copies), 13);
          card.addView(v);
        }
      }
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
    body.removeAllViews();
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
                  CenterStore.IO.execute(
                      () -> {
                        try {
                          CenterMedia.save(m);
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
                  TPAiClient.async(
                      () ->
                          CenterAi.complete(
                              CenterAi.model("reportModel"),
                              "Translate the supplied transcript to the requested language."
                                  + " Preserve every timestamp and distinguish inaudible speech.",
                              language + "\n" + m.transcript),
                      (text, e) -> {
                        if (e != null) error(e);
                        else {
                          m.description = text;
                          CenterStore.IO.execute(
                              () -> {
                                try {
                                  CenterMedia.save(m);
                                } catch (Exception failure) {
                                  FileLog.e(failure);
                                }
                              });
                        }
                      });
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
    button("period", () -> show(0));
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
    button(
        "sort",
        () ->
            choices(
                "sort",
                new String[] {t("messages"), t("authors"), t("media"), t("growth")},
                i -> {
                  activitySort = i;
                  show(2);
                }));
    button(
        "chart",
        () ->
            choices(
                "chart",
                new String[] {t("rank"), t("days"), t("heatmap"), t("content"), t("concentration")},
                i -> {
                  activityMode = i;
                  show(2);
                }));
    button(
        "groups",
        () -> {
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
        });
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
      body.addView(chart, new LinearLayout.LayoutParams(-1, dp(activityMode == 2 ? 280 : 320)));
      button("export", () -> shareChart(chart));
    }
    heading("table");
    for (CenterStats.Group g : ranked) {
      String growth =
          g.previousComplete
              ? (g.previous == 0 && g.messages > 0
                  ? t("new")
                  : String.format(Locale.getDefault(), "%+.1f%%", g.growth() * 100))
              : t("partial");
      row(
          g.title
              + " · "
              + g.messages
              + " · "
              + t("authors")
              + ": "
              + g.authors.size()
              + " · "
              + t("media")
              + ": "
              + g.media
              + "\n"
              + t("average")
              + ": "
              + String.format(Locale.getDefault(), "%.1f", g.messages / (double) stats.days)
              + " · "
              + t("growth")
              + ": "
              + growth
              + "\n"
              + t("concentration")
              + ": "
              + g.concentration
              + (g.currentComplete ? "" : " · " + t("partial")),
          () -> messageDetails(g.evidence));
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
    heading("forwarded");
    for (Map.Entry<String, List<CenterData.Message>> forward : stats.forwards.entrySet())
      row(
          forward.getKey() + " · " + forward.getValue().size(),
          () -> messageDetails(forward.getValue()));
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
    body.removeAllViews();
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
    button(
        "search",
        () ->
            input(
                "search",
                authorSearch,
                v -> {
                  authorSearch = v;
                  authorListPage = 0;
                  show(3);
                }));
    button(
        "sort",
        () ->
            choices(
                "sort",
                new String[] {t("messages"), t("media"), t("groups"), t("growth")},
                i -> {
                  authorSort = i;
                  authorListPage = 0;
                  show(3);
                }));
    button(
        "compareAuthors",
        () -> {
          body.removeAllViews();
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
    for (CenterStats.Author a : authors)
      if (a.name.toLowerCase(Locale.ROOT).contains(authorSearch.toLowerCase(Locale.ROOT))
          && rendered++ >= authorListPage * 40
          && rendered <= (authorListPage + 1) * 40) {
        CheckBox compare = new CheckBox(getParentActivity());
        compare.setText(
            a.name
                + " · "
                + a.count
                + " · "
                + a.groups.size()
                + (a.own ? " · " + t("ownAuthor") : ""));
        compare.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        compare.setChecked(comparedAuthors.contains(a.id));
        compare.setOnCheckedChangeListener(
            (v, b) -> {
              if (b) comparedAuthors.add(a.id);
              else comparedAuthors.remove(a.id);
            });
        body.addView(compare);
        row(t("profile") + " · " + a.name, () -> authorProfile(a, stats));
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
    body.removeAllViews();
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
    final int[] colors = {0xff3185cc, 0xff22a889, 0xffb56bba, 0xffd98a43};

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

    void text(Canvas c, String label, float x, float y) {
      paint.setColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
      paint.setTextSize(dp(11));
      c.drawText(label, x, y, paint);
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      hits.clear();
      data.clear();
      canvas.drawColor(getThemedColor(Theme.key_windowBackgroundWhite));
      float width = getWidth() - dp(24), height = getHeight() - dp(44), left = dp(12), top = dp(20);
      if (mode == 0 || mode == 3 || mode == 4) {
        int max = 1;
        for (CenterStats.Group g : groups)
          max = Math.max(max, mode == 4 ? g.concentration : g.messages);
        float row = height / Math.max(1, groups.size());
        int n = 0;
        for (CenterStats.Group g : groups) {
          float y = top + n * row;
          text(
              canvas, g.title.length() > 28 ? g.title.substring(0, 28) : g.title, left, y + dp(12));
          float x = left, end = left + width * (mode == 4 ? g.concentration : g.messages) / max;
          if (mode == 3) {
            int k = 0;
            for (Map.Entry<String, Integer> type : g.types.entrySet()) {
              float edge = x + width * type.getValue() / max;
              paint.setColor(colors[k++ % colors.length]);
              canvas.drawRect(x, y + dp(20), edge, y + row - dp(8), paint);
              x = edge;
            }
          } else {
            paint.setColor(
                g.previousComplete
                    ? (g.growth() >= 0 ? 0xff22a889 : 0xffd35e62)
                    : colors[n % colors.length]);
            canvas.drawRect(x, y + dp(20), end, y + row - dp(8), paint);
          }
          hit(left, y, left + width, y + row, g.evidence);
          n++;
        }
      } else if (mode == 1) {
        SortedSet<String> dates = new TreeSet<>();
        int max = 1;
        for (CenterStats.Group g : groups) {
          dates.addAll(g.daily.keySet());
          for (int n : g.daily.values()) max = Math.max(max, n);
        }
        List<String> days = new ArrayList<>(dates);
        for (int g = 0; g < groups.size(); g++) {
          CenterStats.Group group = groups.get(g);
          Path line = new Path();
          for (int i = 0; i < days.size(); i++) {
            float x = left + width * i / Math.max(1, days.size() - 1),
                y = top + height - height * group.daily.getOrDefault(days.get(i), 0) / max;
            if (i == 0) line.moveTo(x, y);
            else line.lineTo(x, y);
            List<CenterData.Message> rows =
                group.dayEvidence.getOrDefault(days.get(i), Collections.emptyList());
            hit(x - dp(12), y - dp(12), x + dp(12), y + dp(12), rows);
          }
          paint.setColor(colors[g % colors.length]);
          paint.setStyle(Paint.Style.STROKE);
          paint.setStrokeWidth(dp(2));
          canvas.drawPath(line, paint);
          paint.setStyle(Paint.Style.FILL);
          text(canvas, group.title, left, top + g * dp(14));
        }
      } else if (mode == 2 && !groups.isEmpty()) {
        CenterStats.Group group = groups.get(0);
        int max = 1;
        for (int[] day : group.heat) for (int count : day) max = Math.max(max, count);
        float cw = width / 24, ch = height / 7;
        for (int d = 0; d < 7; d++)
          for (int h = 0; h < 24; h++) {
            paint.setColor(
                android.graphics.Color.argb(30 + 225 * group.heat[d][h] / max, 35, 130, 200));
            float x = left + h * cw, y = top + d * ch;
            canvas.drawRect(x, y, x + cw - 1, y + ch - 1, paint);
            List<CenterData.Message> rows =
                group.hourEvidence.getOrDefault(d + ":" + h, Collections.emptyList());
            hit(x, y, x + cw, y + ch, rows);
          }
        String[] weekdays = new java.text.DateFormatSymbols().getShortWeekdays();
        for (int d = 0; d < 7; d++)
          text(canvas, weekdays[(d + 1) % 7 + 1], left, top + (d + .5f) * ch);
        for (int h = 0; h < 24; h += 6) text(canvas, "" + h, left + h * cw, getHeight() - dp(5));
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
    body.removeAllViews();
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
    body.removeAllViews();
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
    button("fastModel", () -> chooseRole("fastModel", "fastModel"));
    button("reportModel", () -> chooseRole("reportModel", "reportModel"));
    button("visionModel", () -> chooseRole("visionModel", "visionModel"));
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
    button("schedule", this::digestList);
    button("clearHistorySource", this::clearHistorySource);
    toggle(
        "removeDeleted",
        o.optBoolean("removeDeleted", false),
        v -> CenterIndex.setting("removeDeleted", v));
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
    toggle("local", o.optBoolean("localOnly", false), b -> CenterIndex.setting("localOnly", b));
    toggle(
        "secure",
        o.optBoolean("secure", true),
        b -> {
          CenterIndex.setting("secure", b);
          applySecure();
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
    info(t("indexSize") + ": " + CenterStore.get().bytes());
    button("aiConnections", () -> presentFragment(new TelePremiumAiActivity()));
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
