package org.telegram.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.telepremium.TPStrings;
import org.telegram.messenger.telepremium.ai.TPAiClient;
import org.telegram.messenger.telepremium.ai.TPAiOAuth;
import org.telegram.messenger.telepremium.ai.TPAiProvider;
import org.telegram.messenger.telepremium.ai.TPAiStore;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * AI connections: API key + endpoint for the whole OmniChat provider catalog (OpenAI-compatible
 * and Anthropic), GigaChat and Yandex specifics, and account sign-in through OAuth PKCE
 * (OpenRouter, Polza.AI). The default connection powers the article editor assistant.
 */
public class TelePremiumAiActivity extends BaseFragment {
    private TelePremiumSettingsUi ui;
    private TPAiOAuth.Session oauth;

    private static String L(String ru, String en) { return TPStrings.isRussian() ? ru : en; }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(L("ИИ-подключения", "AI connections"));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        ui = new TelePremiumSettingsUi(this, context);
        fragmentView = ui.createScreen();
        render();
        return fragmentView;
    }

    @Override
    public void onFragmentDestroy() {
        if (oauth != null) oauth.cancel();
        super.onFragmentDestroy();
    }

    private void render() {
        if (ui == null) return;
        ui.clear();
        ui.hero(L("Свой ИИ", "Your own AI"),
                L("Модели по API-ключу или через аккаунт — для редактора статей, а позже и для ботов",
                        "Models by API key or account sign-in — for the article editor and, later, bots"),
                null, R.drawable.tp_ic_ai);

        List<TPAiStore.Connection> all = TPAiStore.list();
        TPAiStore.Connection def = TPAiStore.getDefault();
        ui.section(L("Подключения", "Connections"));
        if (all.isEmpty()) {
            ui.row(L("Пока нет подключений", "No connections yet"),
                    L("Добавьте ключ любого провайдера или войдите через OpenRouter", "Add a key of any provider or sign in with OpenRouter"),
                    R.drawable.tp_ic_ai, TelePremiumSettingsUi.SLATE, null, null);
        }
        for (TPAiStore.Connection c : all) {
            TPAiProvider p = TPAiProvider.find(getParentActivity(), c.providerId);
            String sub = (p == null ? c.providerId : p.name) + "\n" + (TextUtils.isEmpty(c.model) ? L("модель не выбрана", "no model") : c.model);
            boolean isDefault = def != null && def.id.equals(c.id);
            ui.row(c.title(), sub, R.drawable.tp_ic_ai, isDefault ? TelePremiumSettingsUi.PURPLE : TelePremiumSettingsUi.INDIGO,
                    isDefault ? L("Основное", "Default") : null, v -> showActions(c));
        }

        ui.section(L("Добавить", "Add"));
        ui.row(L("По API-ключу", "With API key"),
                L("OpenAI, Anthropic, Gemini, DeepSeek, Mistral, Groq, xAI, GigaChat, YandexGPT, Ollama и ещё 80+",
                        "OpenAI, Anthropic, Gemini, DeepSeek, Mistral, Groq, xAI, GigaChat, YandexGPT, Ollama and 80+ more"),
                R.drawable.tp_ic_key, TelePremiumSettingsUi.BLUE, null, v -> pickProvider());
        ui.row(L("Войти через OpenRouter", "Sign in with OpenRouter"),
                L("OAuth PKCE · сотни моделей одним аккаунтом, ключ выдаётся автоматически",
                        "OAuth PKCE · hundreds of models with one account, the key is issued automatically"),
                R.drawable.tp_ic_people, TelePremiumSettingsUi.GREEN, null, v -> startOAuth(TPAiProvider.OPENROUTER_ACCOUNT));
        ui.row(L("Войти через Polza.AI", "Sign in with Polza.AI"),
                L("OAuth PKCE · агрегатор моделей с оплатой в рублях", "OAuth PKCE · model aggregator billed in rubles"),
                R.drawable.tp_ic_people, TelePremiumSettingsUi.TEAL, null, v -> startOAuth(TPAiProvider.POLZA_ACCOUNT));
        ui.row(L("Свой сервер", "Own server"),
                L("Любой OpenAI-compatible адрес: LM Studio, Ollama, vLLM, LiteLLM…", "Any OpenAI-compatible URL: LM Studio, Ollama, vLLM, LiteLLM…"),
                R.drawable.tp_ic_device, TelePremiumSettingsUi.SLATE, null, v -> {
                    TPAiProvider custom = TPAiProvider.find(getParentActivity(), TPAiProvider.CUSTOM);
                    if (custom != null) editConnection(custom, null);
                });

        ui.note(L("Ключи шифруются ключом Android Keystore и отправляются только на адрес своего провайдера. "
                        + "Основное подключение используется кнопкой ✨ в редакторе статей.",
                "Keys are encrypted with an Android Keystore key and are sent only to their own provider. "
                        + "The default connection powers the ✨ button in the article editor."));
    }

    // ------------------------------------------------------------------ actions

    private void showActions(TPAiStore.Connection c) {
        if (getParentActivity() == null) return;
        String[] items = {
                L("Сделать основным", "Make default"),
                L("Проверить", "Test"),
                L("Выбрать модель", "Choose model"),
                L("Изменить", "Edit"),
                L("Удалить", "Delete")
        };
        new AlertDialog.Builder(getParentActivity()).setTitle(c.title()).setItems(items, (d, which) -> {
            switch (which) {
                case 0: TPAiStore.setDefault(c.id); render(); break;
                case 1: test(c); break;
                case 2: chooseModel(c, null, model -> { c.model = model; TPAiStore.save(c, null); render(); }); break;
                case 3: {
                    TPAiProvider p = TPAiProvider.find(getParentActivity(), c.providerId);
                    if (p == null) p = TPAiProvider.find(getParentActivity(), TPAiProvider.CUSTOM);
                    editConnection(p, c);
                    break;
                }
                case 4:
                    new AlertDialog.Builder(getParentActivity()).setTitle(L("Удалить подключение?", "Delete connection?"))
                            .setMessage(c.title())
                            .setPositiveButton(L("Удалить", "Delete"), (d2, w2) -> { TPAiStore.delete(c.id); render(); })
                            .setNegativeButton(L("Отмена", "Cancel"), null).show();
                    break;
            }
        }).show();
    }

    private void test(TPAiStore.Connection c) {
        toast(L("Проверяю…", "Testing…"));
        TPAiClient.async(() -> TPAiClient.complete(c, "Reply with one short word.", "ping"), (text, error) -> {
            if (error != null) alert(L("Ошибка", "Error"), error);
            else alert(L("Работает", "Works"), c.model + ":\n" + (text.length() > 200 ? text.substring(0, 200) : text));
        });
    }

    private void pickProvider() {
        if (getParentActivity() == null) return;
        ArrayList<TPAiProvider> providers = new ArrayList<>();
        for (TPAiProvider p : TPAiProvider.all(getParentActivity())) if (!p.isAccount()) providers.add(p);
        ArrayList<String> labels = new ArrayList<>();
        for (TPAiProvider p : providers) labels.add(p.name + (TextUtils.isEmpty(p.category) ? "" : "  ·  " + p.category));
        pickFromList(L("Провайдер", "Provider"), labels, index -> editConnection(providers.get(index), null));
    }

    private void startOAuth(String providerId) {
        if (getParentActivity() == null) return;
        if (oauth != null) oauth.cancel();
        try {
            oauth = TPAiOAuth.start(providerId, new TPAiOAuth.Listener() {
                @Override public void onKey(String apiKey) {
                    oauth = null;
                    TPAiProvider p = TPAiProvider.find(getParentActivity(), providerId);
                    TPAiStore.Connection c = new TPAiStore.Connection();
                    c.providerId = providerId;
                    c.name = TPAiProvider.OPENROUTER_ACCOUNT.equals(providerId) ? "OpenRouter" : "Polza.AI";
                    c.baseUrl = p == null ? "" : p.baseUrl;
                    c.protocol = TPAiProvider.CHAT;
                    c.authType = TPAiProvider.AUTH_BEARER;
                    c.model = p == null ? "" : p.defaultModel(null);
                    TPAiStore.save(c, apiKey);
                    render();
                    toast(L("Аккаунт подключён", "Account connected"));
                    chooseModel(c, null, model -> { c.model = model; TPAiStore.save(c, null); render(); });
                }

                @Override public void onError(String error) {
                    oauth = null;
                    alert(L("Вход не выполнен", "Sign-in failed"), error);
                }
            });
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(oauth.authUrl));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getParentActivity().startActivity(intent);
            toast(L("Войдите в браузере и вернитесь в TelePremium", "Sign in in the browser and come back"));
        } catch (Exception e) {
            alert(L("Ошибка", "Error"), e.getMessage());
        }
    }

    // ------------------------------------------------------------------ editor

    private void editConnection(TPAiProvider provider, TPAiStore.Connection existing) {
        Context context = getParentActivity();
        if (context == null || provider == null) return;
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(8), AndroidUtilities.dp(22), 0);
        EditText name = field(context, box, L("Название", "Name"), existing != null ? existing.name : provider.name, InputType.TYPE_CLASS_TEXT);
        EditText base = field(context, box, L("Адрес API", "API URL"), existing != null ? existing.baseUrl : provider.baseUrl,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText key = null;
        if (provider.needsKey() || TPAiProvider.CUSTOM.equals(provider.id) || TPAiProvider.AUTH_NONE.equals(provider.authType)) {
            String hint = TPAiProvider.AUTH_GIGACHAT.equals(provider.authType) ? L("Ключ авторизации (Basic)", "Authorization key (Basic)")
                    : TPAiProvider.AUTH_NONE.equals(provider.authType) ? L("API-ключ (если нужен)", "API key (optional)")
                    : L("API-ключ", "API key");
            if (existing != null) hint += L(" — пусто = не менять", " — empty = keep");
            key = field(context, box, hint, "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        EditText scope = null;
        if (provider.needsScope()) {
            String preset = existing != null ? existing.scope : TPAiProvider.AUTH_GIGACHAT.equals(provider.authType) ? "GIGACHAT_API_PERS" : "";
            scope = field(context, box, provider.scopeHint(TPStrings.isRussian()), preset, InputType.TYPE_CLASS_TEXT);
        }
        EditText model = field(context, box, L("Модель (можно выбрать из списка после сохранения)", "Model (pick from the list after saving)"),
                existing != null ? existing.model : provider.defaultModel(null), InputType.TYPE_CLASS_TEXT);
        final EditText keyField = key, scopeField = scope;
        new AlertDialog.Builder(context)
                .setTitle(provider.name)
                .setView(box)
                .setPositiveButton(L("Сохранить", "Save"), (d, w) -> {
                    TPAiStore.Connection c = existing != null ? existing : new TPAiStore.Connection();
                    c.providerId = provider.id;
                    c.name = name.getText().toString().trim();
                    c.baseUrl = base.getText().toString().trim();
                    c.protocol = provider.protocol;
                    c.authType = provider.authType;
                    c.scope = scopeField == null ? "" : scopeField.getText().toString().trim();
                    String m = model.getText().toString().trim();
                    if (TextUtils.isEmpty(m)) m = provider.defaultModel(c.scope);
                    c.model = m;
                    if (TextUtils.isEmpty(c.baseUrl)) { alert(L("Ошибка", "Error"), L("Укажите адрес API", "Enter the API URL")); return; }
                    String secret = keyField == null ? (existing == null ? "" : null) : keyField.getText().toString().trim();
                    if (existing != null && TextUtils.isEmpty(secret)) secret = null;
                    if (existing == null && provider.needsKey() && TextUtils.isEmpty(secret)) {
                        alert(L("Ошибка", "Error"), L("Укажите ключ", "Enter the key"));
                        return;
                    }
                    TPAiStore.save(c, secret);
                    render();
                    if (TextUtils.isEmpty(c.model)) chooseModel(c, null, picked -> { c.model = picked; TPAiStore.save(c, null); render(); });
                    else test(c);
                })
                .setNegativeButton(L("Отмена", "Cancel"), null)
                .show();
    }

    private EditText field(Context context, LinearLayout box, String hint, String value, int inputType) {
        TextView label = new TextView(context);
        label.setText(hint);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        label.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        label.setPadding(0, AndroidUtilities.dp(10), 0, 0);
        box.addView(label);
        EditText edit = new EditText(context);
        edit.setInputType(inputType);
        edit.setSingleLine(true);
        edit.setText(value == null ? "" : value);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        edit.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        edit.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText));
        box.addView(edit, new LinearLayout.LayoutParams(-1, -2));
        return edit;
    }

    // ------------------------------------------------------------------ model list

    interface Picked { void run(String value); }

    private void chooseModel(TPAiStore.Connection c, String keyOverride, Picked picked) {
        toast(L("Загружаю список моделей…", "Loading models…"));
        TPAiClient.async(() -> keyOverride == null ? TPAiClient.models(c) : TPAiClient.models(c, keyOverride), (models, error) -> {
            if (error != null || models == null || models.isEmpty()) {
                alert(L("Список моделей недоступен", "Model list unavailable"),
                        (error == null ? L("Провайдер не вернул модели", "The provider returned no models") : error)
                                + L("\n\nВведите идентификатор модели вручную через «Изменить».", "\n\nType the model id manually via Edit."));
                return;
            }
            pickFromList(L("Модель", "Model"), new ArrayList<>(models), index -> picked.run(models.get(index)));
        });
    }

    interface Index { void run(int index); }

    /** List dialog with a search field (the provider catalog and model lists are long). */
    private void pickFromList(String title, List<String> labels, Index onPick) {
        Context context = getParentActivity();
        if (context == null) return;
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        EditText search = new EditText(context);
        search.setHint(L("Поиск", "Search"));
        search.setSingleLine(true);
        search.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        search.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.leftMargin = slp.rightMargin = AndroidUtilities.dp(20);
        box.addView(search, slp);
        ArrayList<Integer> visible = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) visible.add(i);
        ArrayList<String> shown = new ArrayList<>(labels);
        int textColor = getThemedColor(Theme.key_windowBackgroundWhiteBlackText);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, shown) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(textColor);
                    ((TextView) v).setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                }
                return v;
            }
        };
        ListView list = new ListView(context);
        list.setAdapter(adapter);
        list.setDivider(null);
        list.setSelector(Theme.getSelectorDrawable(false));
        list.setBackgroundColor(Color.TRANSPARENT);
        box.addView(list, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(380)));
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle(title).setView(box)
                .setNegativeButton(L("Отмена", "Cancel"), null).create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            onPick.run(visible.get(position));
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                String q = s.toString().trim().toLowerCase();
                visible.clear();
                shown.clear();
                for (int i = 0; i < labels.size(); i++) {
                    if (q.isEmpty() || labels.get(i).toLowerCase().contains(q)) { visible.add(i); shown.add(labels.get(i)); }
                }
                adapter.notifyDataSetChanged();
            }
        });
        showDialog(dialog);
    }

    private void alert(String title, String message) {
        if (getParentActivity() == null) return;
        new AlertDialog.Builder(getParentActivity()).setTitle(title).setMessage(message)
                .setPositiveButton("OK", null).show();
    }

    private void toast(String text) {
        if (getParentActivity() != null) Toast.makeText(getParentActivity(), text, Toast.LENGTH_SHORT).show();
    }
}
