package org.telegram.messenger.telepremium.aicenter;

import java.util.*;
import org.json.*;

/** Actual provider usage when reported; clearly separated estimates otherwise. */
public final class CenterUsage {
  public static final class Ticket {
    String day, month;
    long reserved;
    Long observed;
  }

  private static boolean recovered;

  private static synchronized void recover() {
    if (recovered) return;
    recovered = true;
    try {
      JSONObject ledger = CenterIndex.settings().optJSONObject("usageLedger");
      if (ledger == null) return;
      Iterator<String> keys = ledger.keys();
      while (keys.hasNext()) {
        JSONObject row = ledger.optJSONObject(keys.next());
        if (row != null && row.optLong("reserved") > 0) {
          row.put("estimated", row.optLong("estimated") + row.optLong("reserved"))
              .put("reserved", 0);
        }
      }
      CenterIndex.setting("usageLedger", ledger);
    } catch (Exception e) {
      org.telegram.messenger.FileLog.e(e);
    }
  }

  private static final ThreadLocal<Ticket> CURRENT = new ThreadLocal<>();

  public static synchronized Ticket reserve(long estimate) throws Exception {
    recover();
    JSONObject settings = CenterIndex.settings(), ledger = settings.optJSONObject("usageLedger");
    if (ledger == null) ledger = new JSONObject();
    String day = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date()),
        month = day.substring(0, 7);
    JSONObject daily = row(ledger, "d:" + day), monthly = row(ledger, "m:" + month);
    if (total(daily) + estimate > settings.optLong("dailyLimit", 1000000)
        || total(monthly) + estimate > settings.optLong("monthlyLimit", 10000000))
      throw new IllegalStateException(CenterText.get("budgetExceeded"));
    for (JSONObject o : new JSONObject[] {daily, monthly})
      o.put("reserved", o.optLong("reserved") + estimate);
    CenterIndex.setting("usageLedger", ledger);
    Ticket t = new Ticket();
    t.day = day;
    t.month = month;
    t.reserved = estimate;
    CURRENT.set(t);
    return t;
  }

  private static JSONObject row(JSONObject ledger, String key) throws Exception {
    JSONObject o = ledger.optJSONObject(key);
    if (o == null) {
      o = new JSONObject();
      ledger.put(key, o);
    }
    return o;
  }

  private static long total(JSONObject o) {
    return o.optLong("actual") + o.optLong("estimated") + o.optLong("reserved");
  }

  public static void observe(JSONObject response) {
    Ticket t = CURRENT.get();
    if (t == null) return;
    JSONObject u = response.optJSONObject("usage");
    if (u == null) return;
    long total = u.optLong("total_tokens", -1);
    if (total < 0 && u.has("input_tokens"))
      total = u.optLong("input_tokens") + u.optLong("output_tokens");
    if (total >= 0) t.observed = total;
  }

  public static synchronized void finish(Ticket ticket) {
    try {
      JSONObject ledger = CenterIndex.settings().optJSONObject("usageLedger");
      if (ledger == null) ledger = new JSONObject();
      for (String key : new String[] {"d:" + ticket.day, "m:" + ticket.month}) {
        JSONObject o = row(ledger, key);
        o.put("reserved", Math.max(0, o.optLong("reserved") - ticket.reserved));
        String field = ticket.observed == null ? "estimated" : "actual";
        o.put(
            field,
            o.optLong(field) + (ticket.observed == null ? ticket.reserved : ticket.observed));
        o.put("requests", o.optInt("requests") + 1);
      }
      CenterIndex.setting("usageLedger", ledger);
    } catch (Exception e) {
      org.telegram.messenger.FileLog.e(e);
    } finally {
      CURRENT.remove();
    }
  }

  public static String summary() {
    recover();
    JSONObject ledger = CenterIndex.settings().optJSONObject("usageLedger");
    if (ledger == null) return "0";
    String day = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
    StringBuilder s = new StringBuilder();
    for (String key : new String[] {"d:" + day, "m:" + day.substring(0, 7)}) {
      JSONObject o = ledger.optJSONObject(key);
      if (o == null) o = new JSONObject();
      s.append(key.substring(2))
          .append(" · ")
          .append(CenterText.get("actualUsage"))
          .append(": ")
          .append(o.optLong("actual"))
          .append(" · ")
          .append(CenterText.get("estimatedUsage"))
          .append(": ")
          .append(o.optLong("estimated"))
          .append(" · ")
          .append(o.optInt("requests"))
          .append('\n');
    }
    return s.toString();
  }
}
