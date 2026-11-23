package go.poligon.messenger.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Polygon-owned appearance state. No upstream UI classes or resource identifiers are used.
 * Both interface families share this one theme and account-independent preference store.
 */
public final class ShellPreferences {
    public static final class Palette {
        public final String id, name;
        public final boolean dark;
        public final int background, surface, elevated, accent, text, muted, divider, incoming, outgoing;
        public Palette(String id, String name, boolean dark, String background, String surface, String accent, String text) {
            this.id=id; this.name=name; this.dark=dark;
            this.background=Color.parseColor(background);
            this.surface=Color.parseColor(surface);
            this.accent=Color.parseColor(accent);
            this.text=Color.parseColor(text);
            this.elevated=blend(this.surface, dark ? Color.WHITE : Color.BLACK, dark ? .07f : .018f);
            this.muted=blend(this.text, this.background, dark ? .32f : .28f);
            this.divider=blend(this.surface, this.text, dark ? .15f : .12f);
            this.incoming=dark ? blend(this.surface, Color.WHITE, .04f) : this.surface;
            this.outgoing=blend(this.surface, this.accent, dark ? .28f : .14f);
        }
    }

    private static int blend(int from, int to, float a) {
        int r=(int)(Color.red(from)*(1f-a)+Color.red(to)*a);
        int g=(int)(Color.green(from)*(1f-a)+Color.green(to)*a);
        int b=(int)(Color.blue(from)*(1f-a)+Color.blue(to)*a);
        return Color.rgb(r,g,b);
    }

    private static final List<Palette> PALETTES;
    static {
        ArrayList<Palette> p = new ArrayList<>();
        p.add(new Palette("dark_graphite","Тёмный графит",true,"#17212B","#202B36","#63AEE8","#F2F6F9"));
        p.add(new Palette("silver_gray","Серо-серебряная",false,"#F5F7FA","#FFFFFF","#526C82","#1E2B36"));
        p.add(new Palette("soft_green","Нежная зелень",false,"#F2F8EC","#FEFFF9","#338B3D","#203321"));
        p.add(new Palette("frost_morning","Морозное утро",false,"#F1F9FF","#FCFEFF","#2A84C3","#173247"));
        p.add(new Palette("warm_beige","Тёплый беж",false,"#FBF2E6","#FFF9F1","#8F5A35","#3E2D23"));
        p.add(new Palette("lavender_twilight","Лавандовый сумрак",false,"#F4F0FF","#FCFAFF","#7751C8","#322A4B"));
        p.add(new Palette("sea_turquoise","Морская бирюза",false,"#EFFCFC","#FBFFFF","#0F9DA5","#163A3D"));
        p.add(new Palette("pink_sand","Розовый песок",false,"#FFF4F4","#FFFAFA","#D45F7C","#432B33"));
        p.add(new Palette("graphite_ice","Графитовый лёд",false,"#EEF4F7","#FBFDFE","#2E8497","#1E2D35"));
        p.add(new Palette("midnight_amoled","Полночь AMOLED",true,"#000000","#0E0F12","#4FB3F6","#ECEFF3"));
        p.add(new Palette("night_ocean","Ночной океан",true,"#0D1A22","#14242E","#2EC4B6","#E4F1F4"));
        p.add(new Palette("dark_violet","Тёмная фиалка",true,"#14111D","#1D1929","#A084FF","#EEEAF8"));
        p.add(new Palette("sunset_peach","Закатный персик",false,"#FFF2E7","#FFFAF4","#EF7048","#422A20"));
        PALETTES=Collections.unmodifiableList(p);
    }

    private final SharedPreferences store;
    public ShellPreferences(Context context) {
        store=context.getApplicationContext().getSharedPreferences("polygon.shell.v1",Context.MODE_PRIVATE);
    }
    public boolean classic() { return store.getBoolean("classic",true); }
    public void classic(boolean value) { store.edit().putBoolean("classic",value).apply(); }
    public String themeId() { return store.getString("theme","silver_gray"); }
    public void themeId(String value) {
        for (Palette p:PALETTES) if (p.id.equals(value)) {store.edit().putString("theme",value).apply();return;}
    }
    public Palette palette() {
        String id=themeId();
        for(Palette p:PALETTES) if(p.id.equals(id)) return p;
        return PALETTES.get(1);
    }
    public List<Palette> allThemes() { return PALETTES; }
}
