#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "upstream" / "Telegram"


def read(rel):
    path = SRC / rel
    if not path.exists():
        raise SystemExit(f"missing source: {rel}")
    return path, path.read_text(encoding="utf-8")


def replace_once(rel, old, new):
    path, text = read(rel)
    if new in text and old not in text:
        print(f"already patched: {rel}")
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{rel}: expected one match, found {count}: {old[:120]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"patched: {rel}")


theme_activity = "TMessagesProj/src/main/java/org/telegram/ui/ThemeActivity.java"
replace_once(
    theme_activity,
    "import org.telegram.messenger.Utilities;\n",
    "import org.telegram.messenger.Utilities;\nimport org.telegram.messenger.telepremium.theme.TelePremiumThemeController;\n",
)
replace_once(
    theme_activity,
    "    public View createView(Context context) {\n",
    "    public View createView(Context context) {\n        if (currentType == THEME_TYPE_BASIC) {\n            TelePremiumThemeController.ensureShellTheme(context);\n        }\n",
)
replace_once(
    theme_activity,
    """        } else if (currentType == THEME_TYPE_BASIC) {
            actionBar.setTitle(getString("ChatSettings", R.string.ChatSettings));
            ActionBarMenu menu = actionBar.createMenu();
            menuItem = menu.addItem(0, R.drawable.ic_ab_other);
            menuItem.setContentDescription(getString("AccDescrMoreOptions", R.string.AccDescrMoreOptions));
            menuItem.addSubItem(share_theme, R.drawable.msg_share, getString("ShareTheme", R.string.ShareTheme));
            menuItem.addSubItem(edit_theme, R.drawable.msg_edit, getString("EditThemeColors", R.string.EditThemeColors));
            menuItem.addSubItem(create_theme, R.drawable.msg_palette, getString("CreateNewThemeMenu", R.string.CreateNewThemeMenu));
            menuItem.addSubItem(reset_settings, R.drawable.msg_reset, getString("ThemeResetToDefaults", R.string.ThemeResetToDefaults));

            if (getMessagesController().getContentSettings() == null) {
""",
    """        } else if (currentType == THEME_TYPE_BASIC) {
            actionBar.setTitle(getString("ChatSettings", R.string.ChatSettings));
            // TelePremium owns the global palette. The stock overflow actions can edit,
            // create, share or reset the active global theme (including forcing Blue),
            // so they are deliberately not exposed from chat-only settings.
            if (getMessagesController().getContentSettings() == null) {
""",
)
replace_once(
    theme_activity,
    """            newThemeInfoRow = rowCount++;
            themeHeaderRow = rowCount++;

            themeListRow2 = rowCount++;
            themeInfoRow = rowCount++;

            bubbleRadiusHeaderRow = rowCount++;
""",
    """            // Global palette/theme selection is intentionally owned by TelePremiumAppearanceActivity.
            // Keep this screen focused on per-chat/message presentation.
            bubbleRadiusHeaderRow = rowCount++;
""",
)
replace_once(
    theme_activity,
    """            nightThemeRow = rowCount++;
            browserRow = rowCount++;
""",
    """            // Auto night/theme switching would replace the TelePremium shell, so it is hidden here.
            browserRow = rowCount++;
""",
)

launch = "TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java"
replace_once(
    launch,
    "import org.telegram.messenger.Utilities;\n",
    "import org.telegram.messenger.Utilities;\nimport org.telegram.messenger.telepremium.cloud.TelePremiumCloudAccessGate;\n",
)
replace_once(
    launch,
    """                } else if (id == 18) {
                    presentFragment(new TelePremiumCloudActivity(currentAccount));
                    drawerLayoutContainer.closeDrawer(false);
""",
    """                } else if (id == 18) {
                    TelePremiumCloudAccessGate.openCloud(this, currentAccount);
                    drawerLayoutContainer.closeDrawer(false);
""",
)

dialogs = "TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java"
replace_once(
    dialogs,
    "import org.telegram.messenger.telepremium.cloud.TelePremiumServicePeerRegistry;\n",
    "import org.telegram.messenger.telepremium.cloud.TelePremiumServicePeerRegistry;\nimport org.telegram.messenger.telepremium.cloud.TelePremiumCloudAccessGate;\n",
)
replace_once(
    dialogs,
    """            if (!onlySelect) {
                presentFragment(new TelePremiumCloudActivity(currentAccount));
            }
""",
    """            if (!onlySelect) {
                TelePremiumCloudAccessGate.openCloud(this, currentAccount);
            }
""",
)

file_center = "TMessagesProj/src/main/java/org/telegram/ui/TelePremiumFileCenterActivity.java"
replace_once(
    file_center,
    "import org.telegram.messenger.UserObject;\n",
    "import org.telegram.messenger.UserObject;\nimport org.telegram.messenger.telepremium.cloud.TelePremiumCloudAccessGate;\n",
)
replace_once(
    file_center,
    "        cloud.setOnClickListener(v -> presentFragment(new TelePremiumCloudActivity(-1)));\n",
    "        cloud.setOnClickListener(v -> TelePremiumCloudAccessGate.openCloud(this, -1));\n",
)
replace_once(
    file_center,
    "            cell.setOnClickListener(v -> presentFragment(new TelePremiumCloudActivity(accountId)));\n",
    "            cell.setOnClickListener(v -> TelePremiumCloudAccessGate.openCloud(this, accountId));\n",
)

cloud = "TMessagesProj/src/main/java/org/telegram/ui/TelePremiumCloudActivity.java"
replace_once(
    cloud,
    "import org.telegram.messenger.telepremium.cloud.CloudTransferManager;\n",
    "import org.telegram.messenger.telepremium.cloud.CloudTransferManager;\n"
    "import org.telegram.messenger.telepremium.cloud.TelePremiumCloudAccessGate;\n"
    "import org.telegram.messenger.telepremium.cloud.TelePremiumCloudSecuritySession;\n"
    "import org.telegram.messenger.telepremium.cloud.TelePremiumCloudSecurityStore;\n",
)
replace_once(
    cloud,
    "        TextView value = label(compactCount(collectCategoryNodes(kind).size()), 12, false); value.setSingleLine(true); value.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));\n",
    """        boolean protectedCategory = TelePremiumCloudSecurityStore.requiresCategoryUnlock(context, kind);
        TextView value = label(protectedCategory ? "Защищено" : compactCount(collectCategoryNodes(kind).size()), 12, false); value.setSingleLine(true); value.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
""",
)
replace_once(
    cloud,
    "        card.setOnClickListener(v -> { activeCategory = kind; renderBody(); });\n",
    "        card.setOnClickListener(v -> TelePremiumCloudAccessGate.openCategory(this, selectedAccount, kind, () -> { activeCategory = kind; renderBody(); }));\n",
)
replace_once(
    cloud,
    """    private void openNode(AccountNode item) {
        attachTransferManager(item.account);
""",
    """    private void openNode(AccountNode item) {
        if (item.node.type == CloudNode.TYPE_FILE && !TelePremiumCloudAccessGate.canExposeNode(context, item.node)) {
            String protectedCategory = TelePremiumCloudSecurityStore.categoryForNode(item.node);
            TelePremiumCloudAccessGate.openCategory(this, item.account, protectedCategory, () -> openNode(item));
            return;
        }
        TelePremiumCloudSecuritySession.touch(context);
        attachTransferManager(item.account);
""",
)
replace_once(
    cloud,
    """    private void showNodeMenu(AccountNode item) {
        TelePremiumCloudManager manager=TelePremiumCloudManager.getInstance(item.account); CloudNode node=item.node;
""",
    """    private void showNodeMenu(AccountNode item) {
        if (item.node.type == CloudNode.TYPE_FILE && !TelePremiumCloudAccessGate.canExposeNode(context, item.node)) {
            String protectedCategory = TelePremiumCloudSecurityStore.categoryForNode(item.node);
            TelePremiumCloudAccessGate.openCategory(this, item.account, protectedCategory, () -> showNodeMenu(item));
            return;
        }
        TelePremiumCloudSecuritySession.touch(context);
        TelePremiumCloudManager manager=TelePremiumCloudManager.getInstance(item.account); CloudNode node=item.node;
""",
)

replace_once(
    cloud,
    "private List<AccountNode> collectNodes(){ArrayList<AccountNode> out=new ArrayList<>();if(selectedAccount>=0){TelePremiumCloudManager m=TelePremiumCloudManager.getInstance(selectedAccount);for(CloudNode n:m.children(folderId,false))out.add(new AccountNode(selectedAccount,n));}else for(int a:activatedAccounts()){TelePremiumCloudManager m=TelePremiumCloudManager.getInstance(a);for(CloudNode n:m.children(\"root\",false))out.add(new AccountNode(a,n));}return out;}",
    "private List<AccountNode> collectNodes(){ArrayList<AccountNode> out=new ArrayList<>();if(selectedAccount>=0){TelePremiumCloudManager m=TelePremiumCloudManager.getInstance(selectedAccount);for(CloudNode n:m.children(folderId,false))if(n.type==CloudNode.TYPE_FOLDER||TelePremiumCloudAccessGate.canExposeNode(context,n))out.add(new AccountNode(selectedAccount,n));}else for(int a:activatedAccounts()){TelePremiumCloudManager m=TelePremiumCloudManager.getInstance(a);for(CloudNode n:m.children(\"root\",false))if(n.type==CloudNode.TYPE_FOLDER||TelePremiumCloudAccessGate.canExposeNode(context,n))out.add(new AccountNode(a,n));}return out;}",
)
replace_once(
    cloud,
    "private List<AccountNode> collectSearchNodes(String query){ArrayList<AccountNode> out=new ArrayList<>();List<Integer> accounts=selectedAccount>=0?Collections.singletonList(selectedAccount):activatedAccounts();for(int a:accounts)for(CloudNode n:TelePremiumCloudManager.getInstance(a).search(query))out.add(new AccountNode(a,n));Collections.sort(out,(x,y)->Long.compare(y.node.modifiedAt,x.node.modifiedAt));return out;}",
    "private List<AccountNode> collectSearchNodes(String query){ArrayList<AccountNode> out=new ArrayList<>();List<Integer> accounts=selectedAccount>=0?Collections.singletonList(selectedAccount):activatedAccounts();for(int a:accounts)for(CloudNode n:TelePremiumCloudManager.getInstance(a).search(query))if(n.type==CloudNode.TYPE_FOLDER||TelePremiumCloudAccessGate.canExposeNode(context,n))out.add(new AccountNode(a,n));Collections.sort(out,(x,y)->Long.compare(y.node.modifiedAt,x.node.modifiedAt));return out;}",
)
replace_once(
    cloud,
    "private List<AccountNode> collectCategoryNodes(String kind){ArrayList<AccountNode> out=new ArrayList<>();List<Integer> accounts=selectedAccount>=0?Collections.singletonList(selectedAccount):activatedAccounts();for(int a:accounts){TelePremiumCloudManager m=TelePremiumCloudManager.getInstance(a);List<CloudNode> nodes=\"all\".equals(kind)?m.allFiles():\"trash\".equals(kind)?m.trashItems():\"favorites\".equals(kind)?m.favorites():m.category(kind);for(CloudNode n:nodes)out.add(new AccountNode(a,n));}return out;}",
    "private List<AccountNode> collectCategoryNodes(String kind){ArrayList<AccountNode> out=new ArrayList<>();List<Integer> accounts=selectedAccount>=0?Collections.singletonList(selectedAccount):activatedAccounts();for(int a:accounts){TelePremiumCloudManager m=TelePremiumCloudManager.getInstance(a);List<CloudNode> nodes=\"all\".equals(kind)?m.allFiles():\"trash\".equals(kind)?m.trashItems():\"favorites\".equals(kind)?m.favorites():m.category(kind);for(CloudNode n:nodes)if(n.type==CloudNode.TYPE_FOLDER||TelePremiumCloudAccessGate.canExposeNode(context,n))out.add(new AccountNode(a,n));}return out;}",
)

print("TelePremium theme isolation + Cloud security V4 applied")
