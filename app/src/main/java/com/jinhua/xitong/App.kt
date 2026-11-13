package com.jinhua.xitong

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject

// ===================== Model.kt =====================
enum class Kind { TICK, PHOTO, WORK }

data class Item(val text: String, val kind: Kind)

/** 一件事（一段）：几点、叫什么、下面有哪些待办。 */
data class Seg(
    val id: String,
    val min: Int,            // 原定时间：当天的第几分钟（凌晨 5 点前算前一天的深夜，记作 24:00 以后）
    val title: String,
    val note: String,
    val items: List<Item>,
    val fixed: Boolean,      // 固定：别的事推迟时它不挪
    val work: Boolean
) {
    val isMeal: Boolean get() = title.contains("饭")
}

/** 「我的一天」里的一行：每天都一样的事。 */
data class RoutineEntry(
    val time: String,
    val title: String,
    val items: List<String>,
    val photo: Boolean,
    val fixed: Boolean
)

data class Entry(val at: Long, val seg: String, val item: String, val kind: String, val photo: String?)

object Day {
    private const val BOUNDARY = 300
    private val WEEK = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    fun toMin(h: Int, m: Int): Int {
        val v = h * 60 + m
        return if (v < BOUNDARY) v + 1440 else v
    }

    fun parseTime(s: String): Int? {
        val m = Regex("""^\s*(\d{1,2})[:：](\d{2})\s*$""").find(s) ?: return null
        val h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].toInt()
        if (h > 23 || mi > 59) return null
        return toMin(h, mi)
    }

    fun fmt(min: Int): String {
        val v = ((min % 1440) + 1440) % 1440
        return "%02d:%02d".format(v / 60, v % 60)
    }

    fun clock(millis: Long): String {
        val c = Calendar.getInstance()
        c.timeInMillis = millis
        return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    fun key(millis: Long = System.currentTimeMillis()): String {
        val c = Calendar.getInstance()
        c.timeInMillis = millis - BOUNDARY * 60_000L
        return "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    private fun calOf(key: String): Calendar {
        val p = key.split("-").map { it.toInt() }
        val c = Calendar.getInstance()
        c.clear()
        c.set(p[0], p[1] - 1, p[2], 0, 0, 0)
        return c
    }

    fun shift(key: String, n: Int): String {
        val c = calOf(key)
        c.add(Calendar.DAY_OF_MONTH, n)
        return "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    fun label(key: String): String {
        val c = calOf(key)
        return "${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日 · ${WEEK[c.get(Calendar.DAY_OF_WEEK) - 1]}"
    }

    fun millisOf(key: String, min: Int): Long {
        val c = calOf(key)
        c.add(Calendar.MINUTE, min)
        return c.timeInMillis
    }

    /** 某个真实时间在当天是第几分钟。 */
    fun minOf(key: String, millis: Long): Int = ((millis - millisOf(key, 0)) / 60_000L).toInt()

    fun hourOf(min: Int): Int = min / 60 * 60
}

object Plan {
    private val BAR = Regex("""\s*[|｜]\s*""")
    private val TIME = Regex("""^(\d{1,2})[:：](\d{2})\s+(.+)$""")

    fun fromRoutine(list: List<RoutineEntry>): List<Seg> = list.mapNotNull { r ->
        val min = Day.parseTime(r.time) ?: return@mapNotNull null
        val items = r.items.filter { it.isNotBlank() }.map { Item(it.trim(), Kind.TICK) }.toMutableList()
        if (items.isEmpty()) items.add(Item(r.title, Kind.TICK))
        if (r.photo) items.add(Item("拍一张照", Kind.PHOTO))
        Seg("r:${r.time}:${r.title}", min, r.title, "", items, r.fixed, false)
    }

    /** 粘贴进来的工作：每行「10:30 写开题报告第2节 | 最低：写3句话」。 */
    fun parseWork(text: String): List<Seg> = text.lines().mapNotNull { line0 ->
        val m = TIME.find(line0.trim()) ?: return@mapNotNull null
        val h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].toInt()
        if (h > 23 || mi > 59) return@mapNotNull null
        val parts = m.groupValues[3].split(BAR, limit = 2)
        val title = parts[0].trim()
        val fixed = title.contains("[固定]")
        val clean = title.replace("[固定]", "").trim()
        Seg("w:${Day.fmt(Day.toMin(h, mi))}:$clean", Day.toMin(h, mi), clean, parts.getOrElse(1) { "" }.trim(),
            listOf(Item(clean, Kind.WORK)), fixed, true)
    }
}

/** 一天里发生的一切：打的勾、拍的照、推迟、提醒进行到哪一级、贴纸…… */
class DayState(json: JSONObject?) {
    val entries = mutableListOf<Entry>()
    val offset = mutableMapOf<String, Int>()
    val remindAt = mutableMapOf<String, Long>()
    val skipped = mutableSetOf<String>()
    val stickers = mutableListOf<Int>()
    val actual = mutableMapOf<String, Long>()
    val fired = mutableSetOf<String>()
    var escSeg: String? = null
    var escStart = 0L
    var escLevel = 0
    var focusUntil = 0L
    var focusTask = ""
    var lastMealNudge = 0L

    init {
        if (json != null) {
            json.optJSONArray("entries")?.let { a ->
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    entries.add(Entry(o.optLong("at"), o.optString("seg"), o.optString("item"), o.optString("kind"),
                        if (o.isNull("photo")) null else o.optString("photo")))
                }
            }
            json.optJSONObject("offset")?.let { o -> o.keys().forEach { k -> offset[k] = o.optInt(k) } }
            json.optJSONObject("remindAt")?.let { o -> o.keys().forEach { k -> remindAt[k] = o.optLong(k) } }
            json.optJSONObject("actual")?.let { o -> o.keys().forEach { k -> actual[k] = o.optLong(k) } }
            json.optJSONArray("skipped")?.let { a -> for (i in 0 until a.length()) skipped.add(a.getString(i)) }
            json.optJSONArray("stickers")?.let { a -> for (i in 0 until a.length()) stickers.add(a.getInt(i)) }
            json.optJSONArray("fired")?.let { a -> for (i in 0 until a.length()) fired.add(a.getString(i)) }
            escSeg = if (json.isNull("escSeg") || !json.has("escSeg")) null else json.getString("escSeg")
            escStart = json.optLong("escStart")
            escLevel = json.optInt("escLevel")
            focusUntil = json.optLong("focusUntil")
            focusTask = json.optString("focusTask")
            lastMealNudge = json.optLong("lastMealNudge")
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("entries", JSONArray().apply {
            entries.forEach { e ->
                put(JSONObject().apply {
                    put("at", e.at); put("seg", e.seg); put("item", e.item); put("kind", e.kind)
                    put("photo", e.photo ?: JSONObject.NULL)
                })
            }
        })
        put("offset", JSONObject(offset as Map<*, *>))
        put("remindAt", JSONObject(remindAt as Map<*, *>))
        put("actual", JSONObject(actual as Map<*, *>))
        put("skipped", JSONArray(skipped.toList()))
        put("stickers", JSONArray(stickers.toList()))
        put("fired", JSONArray(fired.toList()))
        put("escSeg", escSeg ?: JSONObject.NULL)
        put("escStart", escStart)
        put("escLevel", escLevel)
        put("focusUntil", focusUntil)
        put("focusTask", focusTask)
        put("lastMealNudge", lastMealNudge)
    }
}

/** 所有数据只存在手机里。 */
class Store(context: Context) {
    private val ctx = context.applicationContext
    private val sp = ctx.getSharedPreferences("jhxt", Context.MODE_PRIVATE)
    private val dir = File(ctx.filesDir, "days").apply { mkdirs() }

    // ---------- 我的一天 ----------
    var routine: List<RoutineEntry>
        get() {
            val s = sp.getString("routine", null) ?: return DEFAULT_ROUTINE
            return try {
                val a = JSONArray(s)
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    val arr = o.optJSONArray("items") ?: JSONArray()
                    RoutineEntry(o.optString("time"), o.optString("title"),
                        (0 until arr.length()).map { j -> arr.getString(j) }, o.optBoolean("photo"), o.optBoolean("fixed"))
                }.sortedBy { Day.parseTime(it.time) ?: 0 }
            } catch (e: Exception) {
                DEFAULT_ROUTINE
            }
        }
        set(v) {
            val a = JSONArray()
            v.sortedBy { Day.parseTime(it.time) ?: 0 }.forEach { r ->
                a.put(JSONObject().apply {
                    put("time", r.time); put("title", r.title); put("items", JSONArray(r.items))
                    put("photo", r.photo); put("fixed", r.fixed)
                })
            }
            sp.edit().putString("routine", a.toString()).apply()
            changed()
        }

    fun work(key: String): String = sp.getString("work-$key", "") ?: ""
    fun setWork(key: String, v: String) {
        sp.edit().putString("work-$key", v).apply()
        changed()
    }

    // ---------- 分心 APP ----------
    fun limit(pkg: String): Int = sp.getInt("limit-$pkg", DISTRACT.firstOrNull { it.pkg == pkg }?.defaultMin ?: 10)
    fun setLimit(pkg: String, v: Int) {
        sp.edit().putInt("limit-$pkg", v.coerceIn(1, 60)).apply()
        changed()
    }

    // ---------- 每天 ----------
    fun segs(key: String): List<Seg> = (Plan.fromRoutine(routine) + Plan.parseWork(work(key))).sortedBy { it.min }

    fun state(key: String): DayState {
        val f = File(dir, "$key.json")
        return DayState(if (f.exists()) try { JSONObject(f.readText()) } catch (e: Exception) { null } else null)
    }

    fun save(key: String, st: DayState) {
        val f = File(dir, "$key.json")
        val tmp = File(dir, "$key.json.tmp")
        tmp.writeText(st.toJson().toString())
        tmp.renameTo(f)
        changed()
    }

    private fun changed() {
        listeners.toList().forEach { it() }
    }

    companion object {
        val listeners = mutableSetOf<() -> Unit>()

        data class App(val name: String, val pkg: String, val defaultMin: Int)
        val DISTRACT = listOf(App("抖音", "com.ss.android.ugc.aweme", 10), App("微信", "com.tencent.mm", 5))

        val DEFAULT_ROUTINE = listOf(
            RoutineEntry("08:00", "起床", listOf("上厕所", "喝一杯水", "上秤"), photo = true, fixed = false),
            RoutineEntry("08:25", "准备出门", listOf("带上电脑和饭卡"), photo = false, fixed = false),
            RoutineEntry("08:30", "出门", listOf("买饭", "走到实验室"), photo = false, fixed = false),
            RoutineEntry("09:00", "到实验室", listOf("打开电脑"), photo = false, fixed = false),
            RoutineEntry("12:00", "午饭", listOf("慢慢吃，七分饱就停"), photo = false, fixed = false),
            RoutineEntry("18:00", "晚饭", listOf("慢慢吃，七分饱就停"), photo = false, fixed = false),
            RoutineEntry("00:30", "睡觉", listOf("上床睡觉"), photo = false, fixed = true)
        )
    }
}

// ===================== Engine.kt =====================
/**
 * 进化系统的规则都在这里：
 * - 每件事到了自己的时间就提醒（第1级），5 分钟没动再响（第2级），15 分钟没动全屏铃声（第3级）。
 * - 打一个勾或拍一张照，这件事就算「动了」，提醒停止，并记下真实开始时间。
 * - 推迟：这件事和后面所有没做完、不固定的事一起往后挪；固定的事只推迟提醒。
 * - 这一小时重新来：没做完的放下，不记录。
 * - 一个小时里的事全做完，贴一张贴纸。
 * - 工作类的事「开始」时，锁 25 分钟。
 * - 吃饭的 40 分钟里，每 10 分钟提醒一次慢慢吃。
 */
object Engine {
    const val FOCUS_MS = 25 * 60_000L
    private const val L2_MS = 5 * 60_000L
    private const val L3_MS = 15 * 60_000L
    private const val MEAL_MS = 40 * 60_000L
    private const val MEAL_EVERY = 10 * 60_000L

    enum class Effect { EXPAND, BEEP, VIBRATE, FULLSCREEN, MEAL, FOCUS_END }

    // ---------- 查询 ----------
    fun eff(st: DayState, s: Seg): Int = s.min + (st.offset[s.id] ?: 0)
    fun effMillis(key: String, st: DayState, s: Seg): Long = Day.millisOf(key, eff(st, s))
    fun entriesOf(st: DayState, s: Seg, it: Item) = st.entries.filter { e -> e.seg == s.id && e.item == it.text }
    fun isDone(st: DayState, s: Seg, it: Item): Boolean {
        val es = entriesOf(st, s, it)
        return if (it.kind == Kind.WORK) es.any { e -> e.kind == "finish" } else es.isNotEmpty()
    }
    fun segDone(st: DayState, s: Seg) = s.items.all { isDone(st, s, it) }
    fun acted(st: DayState, s: Seg) = st.skipped.contains(s.id) || st.entries.any { e -> e.seg == s.id }
    fun live(st: DayState, s: Seg) = !st.skipped.contains(s.id)
    fun nowMin(key: String) = Day.minOf(key, System.currentTimeMillis())

    fun inHour(st: DayState, segs: List<Seg>, hour: Int) =
        segs.filter { live(st, it) && Day.hourOf(eff(st, it)) == hour }.sortedBy { eff(st, it) }

    /** 已经到点、还没做完的最近一件事。 */
    fun current(key: String, st: DayState, segs: List<Seg>): Seg? {
        val nm = nowMin(key)
        return segs.filter { live(st, it) && eff(st, it) <= nm && !segDone(st, it) }.maxByOrNull { eff(st, it) }
    }

    /** 推迟了、还没到新时间的事——要一直挂在胶囊上。 */
    fun snoozed(key: String, st: DayState, segs: List<Seg>): Seg? {
        val now = System.currentTimeMillis()
        return segs.filter { s ->
            live(st, s) && !acted(st, s) &&
                (((st.offset[s.id] ?: 0) > 0 && effMillis(key, st, s) > now) || (st.remindAt[s.id] ?: 0L) > now)
        }.minByOrNull { remindTime(key, st, it) }
    }

    fun remindTime(key: String, st: DayState, s: Seg): Long = st.remindAt[s.id] ?: effMillis(key, st, s)

    fun meal(key: String, st: DayState, segs: List<Seg>): Seg? {
        val now = System.currentTimeMillis()
        return segs.firstOrNull { s ->
            s.isMeal && live(st, s) && !segDone(st, s) && now >= effMillis(key, st, s) && now < effMillis(key, st, s) + MEAL_MS
        }
    }

    fun escSeg(st: DayState, segs: List<Seg>): Seg? = st.escSeg?.let { id -> segs.firstOrNull { it.id == id } }

    /** 这一小时里第一件还没做的事，用来拉回走神。 */
    fun todo(key: String, st: DayState, segs: List<Seg>): String? {
        val h = Day.hourOf(nowMin(key))
        for (s in inHour(st, segs, h)) for (it in s.items) if (!isDone(st, s, it)) return it.text
        current(key, st, segs)?.let { s -> s.items.firstOrNull { !isDone(st, s, it) }?.let { return it.text } }
        return null
    }

    // ---------- 动作 ----------
    fun act(ctx: Context, key: String, segId: String, itemText: String, kind: String, photo: String? = null) {
        val store = Store(ctx)
        val segs = store.segs(key)
        val s = segs.firstOrNull { it.id == segId } ?: return
        val st = store.state(key)
        val before = acted(st, s)
        val now = System.currentTimeMillis()
        st.entries.add(Entry(now, s.id, itemText, kind, photo))
        if (!before) st.actual[s.id] = now
        st.remindAt.remove(s.id)
        if (st.escSeg == s.id) { st.escSeg = null; st.escLevel = 0 }
        if (kind == "start") { st.focusUntil = now + FOCUS_MS; st.focusTask = itemText }
        if (kind == "finish" && st.focusTask == itemText) st.focusUntil = 0L
        val h = Day.hourOf(eff(st, s))
        val all = segs.filter { Day.hourOf(eff(st, it)) == h }
        if (all.isNotEmpty() && all.all { live(st, it) && segDone(st, it) } && !st.stickers.contains(h)) st.stickers.add(h)
        store.save(key, st)
        Scheduler.reschedule(ctx)
    }

    fun untick(ctx: Context, key: String, segId: String, itemText: String) {
        val store = Store(ctx)
        val st = store.state(key)
        st.entries.removeAll { it.seg == segId && it.item == itemText && it.kind == "tick" }
        if (st.entries.none { it.seg == segId }) st.actual.remove(segId)
        store.save(key, st)
    }

    /** 推迟：普通的事连同后面的一起挪；固定的事只推迟提醒。返回一句给人看的话。 */
    fun snooze(ctx: Context, key: String, minutes: Int): String {
        val store = Store(ctx)
        val segs = store.segs(key)
        val st = store.state(key)
        val s = escSeg(st, segs) ?: current(key, st, segs) ?: return "现在没有可以推迟的事"
        val now = System.currentTimeMillis()
        st.escSeg = null; st.escLevel = 0
        val msg: String
        if (s.fixed) {
            st.remindAt[s.id] = now + minutes * 60_000L
            msg = "「${s.title}」是固定的，时间不变，${Day.clock(now + minutes * 60_000L)} 再提醒"
        } else {
            val from = eff(st, s)
            val d = (nowMin(key) + minutes) - from
            val moved = segs.filter { x -> live(st, x) && !segDone(st, x) && !x.fixed && (x.id == s.id || eff(st, x) >= from) }
            moved.forEach { x ->
                st.offset[x.id] = (st.offset[x.id] ?: 0) + d
                st.fired.remove(x.id)
            }
            msg = "「${s.title}」改到 ${Day.fmt(from + d)}，后面 ${moved.size - 1} 件事一起往后挪 $d 分钟"
        }
        store.save(key, st)
        Scheduler.reschedule(ctx)
        return msg
    }

    fun restartHour(ctx: Context, key: String) {
        val store = Store(ctx)
        val segs = store.segs(key)
        val st = store.state(key)
        val h = Day.hourOf(nowMin(key))
        inHour(st, segs, h).filter { !segDone(st, it) }.forEach { st.skipped.add(it.id) }
        if (st.escSeg != null && st.skipped.contains(st.escSeg!!)) { st.escSeg = null; st.escLevel = 0 }
        store.save(key, st)
        Scheduler.reschedule(ctx)
    }

    fun emergencyUnlock(ctx: Context) {
        val key = Day.key()
        val store = Store(ctx)
        val st = store.state(key)
        st.entries.add(Entry(System.currentTimeMillis(), "", st.focusTask, "emergency", null))
        st.focusUntil = 0L
        store.save(key, st)
    }

    // ---------- 每隔一会儿检查一次 ----------
    fun evaluate(ctx: Context): Set<Effect> {
        val store = Store(ctx)
        val key = Day.key()
        val segs = store.segs(key)
        val st = store.state(key)
        val now = System.currentTimeMillis()
        val nm = Day.minOf(key, now)
        val fx = mutableSetOf<Effect>()
        var dirty = false

        fun level1(s: Seg) {
            st.escSeg = s.id; st.escStart = now; st.escLevel = 1
            fx += Effect.EXPAND; fx += Effect.BEEP
            dirty = true
        }

        for (s in segs) {
            if (!live(st, s) || acted(st, s) || st.fired.contains(s.id) || st.remindAt.containsKey(s.id)) continue
            val e = eff(st, s)
            if (e <= nm) {
                st.fired.add(s.id); dirty = true
                if (nm - e < 120) level1(s)
            }
        }
        for ((id, t) in st.remindAt.toMap()) {
            if (t <= now) {
                st.remindAt.remove(id); dirty = true
                segs.firstOrNull { it.id == id }?.let { s -> if (!acted(st, s)) level1(s) }
            }
        }
        val es = escSeg(st, segs)
        if (es == null || acted(st, es)) {
            if (st.escSeg != null) { st.escSeg = null; st.escLevel = 0; dirty = true }
        } else {
            if (st.escLevel < 2 && now - st.escStart >= L2_MS) { st.escLevel = 2; fx += Effect.EXPAND; fx += Effect.BEEP; fx += Effect.VIBRATE; dirty = true }
            if (st.escLevel < 3 && now - st.escStart >= L3_MS) { st.escLevel = 3; fx += Effect.FULLSCREEN; dirty = true }
        }
        meal(key, st, segs)?.let { m ->
            if (now - effMillis(key, st, m) >= MEAL_EVERY && now - st.lastMealNudge >= MEAL_EVERY) {
                st.lastMealNudge = now; fx += Effect.MEAL; dirty = true
            }
        }
        if (st.focusUntil in 1..now) { st.focusUntil = 0L; fx += Effect.FOCUS_END; dirty = true }
        if (dirty) store.save(key, st)
        Scheduler.schedule(ctx, nextWake(key, st, segs, now))
        return fx
    }

    private fun nextWake(key: String, st: DayState, segs: List<Seg>, now: Long): Long {
        val c = mutableListOf<Long>()
        segs.filter { live(st, it) && !acted(st, it) && !st.fired.contains(it.id) && !st.remindAt.containsKey(it.id) }
            .map { effMillis(key, st, it) }.filter { it > now }.minOrNull()?.let { c += it }
        st.remindAt.values.filter { it > now }.minOrNull()?.let { c += it }
        if (st.escSeg != null) {
            if (st.escLevel < 2) c += st.escStart + L2_MS
            if (st.escLevel < 3) c += st.escStart + L3_MS
        }
        if (st.focusUntil > now) c += st.focusUntil
        meal(key, st, segs)?.let { c += maxOf(st.lastMealNudge + MEAL_EVERY, effMillis(key, st, it) + MEAL_EVERY) }
        // 第二天 05:01 重新看一遍（新的一天从早上 5 点开始）
        c += Day.millisOf(Day.shift(key, 1), 301)
        return c.filter { it > now }.minOrNull() ?: (now + 15 * 60_000L)
    }
}

// ===================== Alarms.kt =====================
object Scheduler {
    fun schedule(ctx: Context, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, AlarmReceiver::class.java).setAction("jhxt.wake"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** 状态变了：马上重新检查一遍，并排好下一次叫醒。 */
    fun reschedule(ctx: Context) {
        Alert.run(ctx, Engine.evaluate(ctx))
        FloatService.refresh(ctx)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        FloatService.start(ctx)
        Alert.run(ctx, Engine.evaluate(ctx))
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        FloatService.start(ctx)
        Alert.run(ctx, Engine.evaluate(ctx))
    }
}

object Alert {
    const val CH_ALARM = "jhxt_alarm2"
    const val CH_FLOAT = "jhxt_float2"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_ALARM, "到点提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "15 分钟没动时，全屏弹出"
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CH_FLOAT, "这一小时的待办", NotificationManager.IMPORTANCE_LOW).apply {
            description = "锁屏和通知栏上显示这一小时要做的事"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
    }

    fun run(ctx: Context, fx: Set<Engine.Effect>) {
        if (fx.isEmpty()) return
        if (Engine.Effect.EXPAND in fx || Engine.Effect.MEAL in fx) FloatService.expand(ctx)
        if (Engine.Effect.BEEP in fx || Engine.Effect.MEAL in fx || Engine.Effect.FOCUS_END in fx) beep(ctx)
        if (Engine.Effect.VIBRATE in fx) vibrate(ctx)
        if (Engine.Effect.FULLSCREEN in fx) fullScreen(ctx)
        if (Engine.Effect.FOCUS_END in fx) FloatService.refresh(ctx)
    }

    fun beep(ctx: Context) {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(ctx, uri)?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
                play()
            }
        } catch (_: Exception) {
        }
    }

    fun vibrate(ctx: Context) {
        try {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31)
                ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else ctx.getSystemService(Vibrator::class.java)
            v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 600), -1))
        } catch (_: Exception) {
        }
    }

    fun fullScreen(ctx: Context) {
        val open = Intent(ctx, ReminderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, 30, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        channels(ctx)
        val n = Notification.Builder(ctx, CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("已经过了 15 分钟")
            .setContentText("该开始了")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java)?.notify(30, n)
        } catch (_: SecurityException) {
        }
        try {
            ctx.startActivity(open)
        } catch (_: Exception) {
        }
    }
}

// ===================== Ui.kt =====================
/** 灰绿鼠尾草配色。 */
object C {
    val PAPER = Color.parseColor("#F0F1EC")
    val INK = Color.parseColor("#3B4237")
    val QUIET = Color.parseColor("#5E6658")
    val FAINT = Color.parseColor("#9AA190")
    val MAIN = Color.parseColor("#617357")
    val MAIN_SOFT = Color.parseColor("#E1E7DA")
    val MAIN_INK = Color.parseColor("#4C5A44")
    val DOT = Color.parseColor("#A9B89F")
    val SOFT = Color.parseColor("#DDE3D3")
    val SOFT_INK = Color.parseColor("#3F4A39")
    val RED = Color.parseColor("#A9573F")
    val WARM = Color.parseColor("#F5EDE6")
    val CIRCLE = Color.parseColor("#B9C0B0")
    val STICKERS = intArrayOf(
        Color.parseColor("#A9B89F"), Color.parseColor("#E3CFA8"), Color.parseColor("#8FA584"),
        Color.parseColor("#C9A98E"), Color.parseColor("#B8BFA8"), Color.parseColor("#D9B9A0")
    )
}

object Ui {
    private var hand: Typeface? = null

    fun hand(ctx: Context): Typeface {
        hand?.let { return it }
        val t = try { ctx.resources.getFont(R.font.long_cang) } catch (e: Exception) { Typeface.DEFAULT }
        hand = t
        return t
    }

    fun dp(ctx: Context, v: Number): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    fun round(color: Int, radiusDp: Number, ctx: Context, stroke: Int? = null, strokeDp: Number = 1.5): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (stroke != null) setStroke(dp(ctx, strokeDp), stroke)
        }

    /** 手写字。 */
    fun h(ctx: Context, s: CharSequence, sp: Float, color: Int = C.INK): TextView = TextView(ctx).apply {
        text = s
        textSize = sp
        setTextColor(color)
        typeface = hand(ctx)
        includeFontPadding = false
    }

    /** 普通小字。 */
    fun t(ctx: Context, s: CharSequence, sp: Float = 14f, color: Int = C.QUIET): TextView = TextView(ctx).apply {
        text = s
        textSize = sp
        setTextColor(color)
    }

    fun lp(w: Int = LinearLayout.LayoutParams.MATCH_PARENT, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight)

    fun wrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    fun vbox(ctx: Context) = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    fun hbox(ctx: Context) = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    /** 手写的链接：字 + 一道手画的波浪下划线。 */
    fun link(ctx: Context, s: String, color: Int = C.MAIN, sp: Float = 24f, onClick: () -> Unit): View = vbox(ctx).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        minimumHeight = dp(ctx, 44)
        setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 2))
        addView(h(ctx, s, sp, color).apply { maxLines = 1 }, wrap())
        addView(Squiggle(ctx, color, 0.45f), LinearLayout.LayoutParams(dp(ctx, 16 + s.length * 14), dp(ctx, 8)))
        isClickable = true
        setOnClickListener { onClick() }
        contentDescription = s
    }

    fun section(ctx: Context, s: String): View = vbox(ctx).apply {
        addView(h(ctx, s, 28f), wrap())
        addView(Squiggle(ctx, C.DOT, 1f), LinearLayout.LayoutParams(dp(ctx, 64), dp(ctx, 8)))
    }

    fun takePhoto(ctx: Context, key: String, seg: Seg, item: Item, kind: String) {
        ctx.startActivity(Intent(ctx, PhotoActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("key", key); putExtra("seg", seg.id); putExtra("item", item.text); putExtra("kind", kind)
        })
    }

    /**
     * 一组待办：左边一列时间，中间一个手画的勾或圈，右边是事情。
     * 生活小事点一下就打勾；要拍照的点「拍照」；工作点「开始」和「完成」各拍一张。
     */
    fun checklist(ctx: Context, key: String, segs: List<Seg>, big: Boolean = false, showTime: Boolean = true, heads: Boolean = true): LinearLayout {
        val st = Store(ctx).state(key)
        val box = vbox(ctx)
        val sp = if (big) 28f else 24f
        segs.forEach { s ->
            val showHead = heads && (s.items.size > 1 || s.items.first().text != s.title)
            if (showHead) {
                val head = hbox(ctx).apply { setPadding(0, dp(ctx, 6), 0, dp(ctx, 2)) }
                if (showTime) head.addView(h(ctx, Day.fmt(Engine.eff(st, s)), 20f, C.QUIET), LinearLayout.LayoutParams(dp(ctx, 62), -2))
                head.addView(h(ctx, s.title, sp, C.INK), lp(0, weight = 1f))
                val off = st.offset[s.id] ?: 0
                if (off > 0) head.addView(h(ctx, "推迟 $off 分", 17f, C.RED))
                if (s.fixed) head.addView(h(ctx, "固定", 17f, C.QUIET))
                box.addView(head, lp())
            }
            s.items.forEach { item ->
                box.addView(row(ctx, key, st, s, item, sp - 2f, if (showHead || !showTime) "" else Day.fmt(Engine.eff(st, s)), showTime), lp())
            }
            if (s.note.isNotEmpty()) box.addView(t(ctx, s.note, 13f).apply { setPadding(dp(ctx, if (showTime) 94 else 32), 0, 0, dp(ctx, 4)) })
        }
        return box
    }

    private fun row(ctx: Context, key: String, st: DayState, s: Seg, item: Item, sp: Float, time: String, showTime: Boolean): View {
        val done = Engine.isDone(st, s, item)
        val es = Engine.entriesOf(st, s, item)
        val r = hbox(ctx).apply { minimumHeight = dp(ctx, 46) }
        if (showTime) r.addView(h(ctx, time, 20f, C.QUIET), LinearLayout.LayoutParams(dp(ctx, 62), -2))
        r.addView(Mark(ctx, if (done) Mark.CHECK else Mark.CIRCLE), LinearLayout.LayoutParams(dp(ctx, 24), dp(ctx, 24)).apply { marginEnd = dp(ctx, 8) })
        r.addView(h(ctx, item.text, sp, if (done) C.QUIET else C.INK).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, lp(0, weight = 1f))
        when (item.kind) {
            Kind.TICK -> {
                r.isClickable = true
                r.contentDescription = (if (done) "取消：" else "完成：") + item.text
                r.setOnClickListener {
                    if (done) Engine.untick(ctx, key, s.id, item.text) else Engine.act(ctx, key, s.id, item.text, "tick")
                }
            }
            Kind.PHOTO -> {
                if (done) r.addView(h(ctx, "拍了 " + Day.clock(es.last().at), 17f, C.MAIN))
                else r.addView(link(ctx, "拍照", C.MAIN, 21f) { takePhoto(ctx, key, s, item, "photo") })
            }
            Kind.WORK -> {
                val started = es.firstOrNull { it.kind == "start" }
                val finished = es.firstOrNull { it.kind == "finish" }
                when {
                    finished != null -> r.addView(h(ctx, "完成 " + Day.clock(finished.at), 17f, C.MAIN))
                    started != null -> r.addView(link(ctx, "完成·拍结果", C.MAIN, 20f) { takePhoto(ctx, key, s, item, "finish") })
                    else -> r.addView(link(ctx, "开始·拍一张", C.MAIN, 20f) { takePhoto(ctx, key, s, item, "start") })
                }
            }
        }
        return r
    }
}

/** 手画的勾、圈、小点。 */
class Mark(ctx: Context, private val kind: Int, private val color: Int = 0) : View(ctx) {
    companion object { const val CHECK = 0; const val CIRCLE = 1; const val DOT = 2 }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    override fun onDraw(c: Canvas) {
        val s = width / 24f
        c.save(); c.scale(s, s)
        when (kind) {
            CHECK -> {
                p.color = if (color != 0) color else C.MAIN; p.strokeWidth = 2.4f
                c.drawPath(Path().apply { moveTo(3f, 13f); cubicTo(5.5f, 14.6f, 7.4f, 16.8f, 9f, 19.2f); cubicTo(11.6f, 13f, 15.6f, 7.6f, 21f, 3.6f) }, p)
            }
            CIRCLE -> {
                p.color = if (color != 0) color else C.CIRCLE; p.strokeWidth = 1.8f
                c.drawPath(Path().apply {
                    moveTo(12.4f, 3.3f); cubicTo(17.2f, 3.1f, 20.9f, 6.9f, 20.6f, 11.9f)
                    cubicTo(20.3f, 16.8f, 16.4f, 20.6f, 11.6f, 20.3f); cubicTo(6.8f, 20f, 3.5f, 16.2f, 3.8f, 11.6f)
                    cubicTo(4.1f, 7.3f, 7.2f, 4f, 12.7f, 3.5f)
                }, p)
            }
            else -> {
                p.style = Paint.Style.FILL; p.color = if (color != 0) color else Color.parseColor("#C9CEC0")
                c.drawCircle(12f, 12f, 3f, p)
            }
        }
        c.restore()
    }
}

/** 手画的波浪下划线。 */
class Squiggle(ctx: Context, private val color: Int, private val alphaF: Float) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    override fun onDraw(c: Canvas) {
        p.color = color; p.alpha = (255 * alphaF).toInt(); p.strokeWidth = height * 0.32f
        val w = width.toFloat(); val hh = height.toFloat()
        c.drawPath(Path().apply {
            moveTo(w * .02f, hh * .62f); cubicTo(w * .18f, hh * .25f, w * .34f, hh * .88f, w * .52f, hh * .5f)
            cubicTo(w * .68f, hh * .2f, w * .84f, hh * .75f, w * .98f, hh * .38f)
        }, p)
    }
}

/** 贴纸：花、星星、叶子、心、邮票、圆圈，轮流出现。 */
class Sticker(ctx: Context, private val n: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val s = minOf(width, height) / 60f
        c.save(); c.translate((width - 60 * s) / 2, (height - 60 * s) / 2); c.scale(s, s)
        val col = C.STICKERS[n % C.STICKERS.size]
        p.style = Paint.Style.FILL; p.color = col
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.5f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        when (n % 6) {
            0 -> {
                listOf(30f to 16f, 43f to 26f, 38f to 41f, 22f to 41f, 17f to 26f).forEach { (x, y) -> c.drawCircle(x, y, 10f, p) }
                p.color = Color.parseColor("#F3DFB4"); c.drawCircle(30f, 30f, 7f, p)
            }
            1 -> {
                val path = Path().apply {
                    moveTo(30f, 5f); lineTo(37f, 21f); lineTo(54f, 23f); lineTo(41f, 34f); lineTo(45f, 51f)
                    lineTo(30f, 42f); lineTo(15f, 51f); lineTo(19f, 34f); lineTo(6f, 23f); lineTo(23f, 21f); close()
                }
                c.drawPath(path, p); c.drawPath(path, white)
            }
            2 -> {
                c.drawPath(Path().apply { moveTo(10f, 50f); cubicTo(10f, 24f, 26f, 10f, 52f, 8f); cubicTo(50f, 34f, 35f, 50f, 10f, 50f); close() }, p)
                c.drawLine(12f, 48f, 40f, 20f, white)
            }
            3 -> c.drawPath(Path().apply {
                moveTo(30f, 52f); cubicTo(30f, 52f, 7f, 38f, 7f, 22f); cubicTo(7f, 10f, 24f, 8f, 30f, 17f)
                cubicTo(36f, 8f, 53f, 10f, 53f, 22f); cubicTo(53f, 38f, 30f, 52f, 30f, 52f); close()
            }, p)
            4 -> {
                c.drawRoundRect(RectF(3f, 10f, 57f, 50f), 6f, 6f, p)
                white.strokeWidth = 2f
                white.pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 3f), 0f)
                c.drawRoundRect(RectF(8f, 15f, 52f, 45f), 4f, 4f, white)
            }
            else -> {
                c.drawCircle(30f, 30f, 22f, p); c.drawCircle(30f, 30f, 14f, white)
                p.color = Color.WHITE; c.drawCircle(30f, 30f, 5f, p)
            }
        }
        c.restore()
    }
}

/** 胶囊左边的小图标：进度圈、铃铛、时钟、碗、勾、月亮。 */
class CapIcon(ctx: Context) : View(ctx) {
    companion object { const val RING = 0; const val BELL = 1; const val CLOCK = 2; const val BOWL = 3; const val CHECK = 4; const val MOON = 5; const val CAMERA = 6; const val PIN = 7 }
    var kind = RING
    var frac = 0f
    var color = Color.WHITE
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    override fun onDraw(c: Canvas) {
        val s = width / 24f
        c.save(); c.scale(s, s)
        p.color = color; p.strokeWidth = 2.2f; p.alpha = 255
        when (kind) {
            RING -> {
                p.alpha = 80; p.strokeWidth = 3f; c.drawCircle(12f, 12f, 9f, p)
                p.alpha = 255; c.drawArc(RectF(3f, 3f, 21f, 21f), -90f, 360f * frac.coerceIn(0f, 1f), false, p)
            }
            BELL -> {
                c.drawPath(Path().apply { moveTo(6f, 16.5f); cubicTo(7f, 15f, 7f, 12f, 7f, 9f); cubicTo(7f, 6f, 9.2f, 4f, 12f, 4f); cubicTo(14.8f, 4f, 17f, 6f, 17f, 9f); cubicTo(17f, 12f, 17f, 15f, 18f, 16.5f); close() }, p)
                c.drawLine(10.5f, 19.5f, 13.5f, 19.5f, p)
            }
            CLOCK -> { c.drawCircle(12f, 12f, 8.5f, p); c.drawLine(12f, 7.5f, 12f, 12f, p); c.drawLine(12f, 12f, 15f, 14f, p) }
            BOWL -> {
                c.drawPath(Path().apply { moveTo(3.5f, 11f); lineTo(20.5f, 11f); cubicTo(20.5f, 16f, 16.5f, 20f, 12f, 20f); cubicTo(7.5f, 20f, 3.5f, 16f, 3.5f, 11f); close() }, p)
                c.drawLine(9f, 4f, 9f, 8f, p); c.drawLine(13f, 3.5f, 13f, 8f, p); c.drawLine(17f, 4f, 17f, 8f, p)
            }
            CHECK -> { p.strokeWidth = 2.8f; c.drawPath(Path().apply { moveTo(5f, 12.5f); lineTo(9.5f, 17f); lineTo(19f, 7.5f) }, p) }
            CAMERA -> {
                p.strokeWidth = 1.9f
                c.drawPath(Path().apply { moveTo(4f, 8.5f); lineTo(8f, 8.5f); lineTo(9.5f, 6f); lineTo(14.5f, 6f); lineTo(16f, 8.5f); lineTo(20f, 8.5f); lineTo(20f, 18f); lineTo(4f, 18f); close() }, p)
                c.drawCircle(12f, 12.8f, 3.2f, p)
            }
            PIN -> {
                p.strokeWidth = 1.9f
                c.drawPath(Path().apply { moveTo(9f, 4f); lineTo(15f, 4f); lineTo(14f, 9f); lineTo(17f, 12f); lineTo(7f, 12f); lineTo(10f, 9f); close() }, p)
                c.drawLine(12f, 12f, 12f, 20f, p)
            }
            else -> c.drawPath(Path().apply { moveTo(19f, 14.5f); cubicTo(15f, 16f, 9f, 12f, 10f, 5f); cubicTo(6f, 6.5f, 4f, 10f, 4.5f, 13.5f); cubicTo(5.5f, 18f, 10f, 20.5f, 14f, 19.5f); cubicTo(16.5f, 19f, 18.3f, 17f, 19f, 14.5f); close() }, p)
        }
        c.restore()
    }
}

// ===================== FloatService.kt =====================
/**
 * 一直浮在屏幕最上面的胶囊（像灵动岛），点开是这一小时的待办；
 * 专注时盖住整个屏幕；打开分心 APP 时把人拉回来。
 */
class FloatService : Service() {
    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var capsule: LinearLayout? = null
    private var capIcon: CapIcon? = null
    private var capText: TextView? = null
    private var capChip: TextView? = null
    private var panel: LinearLayout? = null
    private var lockView: View? = null
    private var pulse: ObjectAnimator? = null
    private var expanded = false

    // 走神检测
    private var fgPkg: String? = null
    private var sessionPkg: String? = null
    private var sessionStart = 0L
    private var pulledFor: String? = null
    private var interrupted = false

    private val onChange: () -> Unit = { handler.post { refresh() } }
    private val slowTick = object : Runnable {
        override fun run() {
            Alert.run(this@FloatService, Engine.evaluate(this@FloatService))
            refresh()
            handler.postDelayed(this, 15_000)
        }
    }
    private val fastTick = object : Runnable {
        override fun run() {
            checkUsage()
            updateLock()
            handler.postDelayed(this, 2_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        Alert.channels(this)
        goForeground("进化系统在运行", "", emptyList())
        Store.listeners.add(onChange)
        attach()
        handler.post(slowTick)
        handler.post(fastTick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground("进化系统在运行", "", emptyList())
        if (root == null) attach()
        when (intent?.action) {
            ACTION_EXPAND -> expanded = true
            ACTION_COLLAPSE -> expanded = false
        }
        refresh()
        return START_STICKY
    }

    override fun onDestroy() {
        Store.listeners.remove(onChange)
        handler.removeCallbacksAndMessages(null)
        listOf(root, lockView).forEach { v -> v?.let { try { wm.removeView(it) } catch (_: Exception) {} } }
        root = null; lockView = null
        super.onDestroy()
    }

    // ---------- 前台通知（锁屏上显示这一小时的待办） ----------
    private fun goForeground(title: String, sub: String, lines: List<String>) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, Alert.CH_FLOAT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(if (sub.isNotEmpty()) sub else lines.firstOrNull() ?: "")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open)
        if (lines.isNotEmpty()) {
            val style = Notification.InboxStyle().setBigContentTitle(title)
            lines.forEach { style.addLine(it) }
            if (sub.isNotEmpty()) style.setSummaryText(sub)
            b.setStyle(style)
        }
        val n = b.build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(1, n)
        } catch (_: Exception) {
        }
    }

    // ---------- 胶囊 ----------
    private fun d(v: Number) = Ui.dp(this, v)

    private fun overlayParams(full: Boolean) = WindowManager.LayoutParams(
        if (full) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
        if (full) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        if (!full) y = d(6)
    }

    private fun attach() {
        if (!Settings.canDrawOverlays(this) || root != null) return
        val r = Ui.vbox(this).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(d(10), d(4), d(10), d(10)) }
        val icon = CapIcon(this)
        val text = Ui.h(this, "", 19f, Color.WHITE).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = d(190) }
        val chip = Ui.t(this, "", 11f, Color.WHITE).apply { setPadding(d(8), d(2), d(8), d(2)); maxLines = 1 }
        val cap = Ui.hbox(this).apply {
            setPadding(d(5), d(5), d(12), d(5))
            elevation = d(6).toFloat()
            addView(icon, LinearLayout.LayoutParams(d(28), d(28)).apply { marginEnd = d(8) })
            addView(text, Ui.wrap())
            addView(chip, Ui.wrap().apply { marginStart = d(8) })
            isClickable = true
            setOnClickListener { expanded = !expanded; refresh() }
        }
        val pan = Ui.vbox(this).apply {
            background = Ui.round(C.PAPER, 22, this@FloatService)
            elevation = d(10).toFloat()
            setPadding(d(18), d(14), d(18), d(12))
            visibility = View.GONE
        }
        r.addView(cap, Ui.wrap())
        r.addView(pan, LinearLayout.LayoutParams(d(340), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = d(8) })
        try {
            wm.addView(r, overlayParams(false))
            root = r; capsule = cap; capIcon = icon; capText = text; capChip = chip; panel = pan
        } catch (_: Exception) {
        }
    }

    private enum class Look { WAIT, MAIN, SOFT }

    fun refresh() {
        val key = Day.key()
        val store = Store(this)
        val segs = store.segs(key)
        val st = store.state(key)
        val now = System.currentTimeMillis()
        val nm = Day.minOf(key, now)
        val hour = Day.hourOf(nm)
        val inHour = Engine.inHour(st, segs, hour)
        var dn = 0; var tot = 0
        inHour.forEach { s -> s.items.forEach { tot++; if (Engine.isDone(st, s, it)) dn++ } }
        val h = (hour / 60) % 24

        val esc = Engine.escSeg(st, segs)?.takeIf { !Engine.acted(st, it) }
        val meal = Engine.meal(key, st, segs)
        val snz = Engine.snoozed(key, st, segs)
        val cur = Engine.current(key, st, segs)
        val look: Look
        val text: String
        val chip: String
        when {
            st.focusUntil > now -> {
                look = Look.MAIN; capIcon?.kind = CapIcon.RING
                capIcon?.frac = 1f - (st.focusUntil - now).toFloat() / Engine.FOCUS_MS
                text = "专注 · ${st.focusTask}"; chip = "还剩 ${((st.focusUntil - now) / 60_000L) + 1} 分"
            }
            esc != null -> { look = Look.WAIT; capIcon?.kind = CapIcon.BELL; text = "该开始了：${esc.title}"; chip = Day.fmt(Engine.eff(st, esc)) }
            meal != null -> { look = Look.SOFT; capIcon?.kind = CapIcon.BOWL; text = "${meal.title} · 慢慢吃"; chip = "七分饱就停" }
            snz != null -> { look = Look.MAIN; capIcon?.kind = CapIcon.CLOCK; text = "已推迟 · ${snz.title}"; chip = Day.clock(Engine.remindTime(key, st, snz)) + " 再提醒" }
            cur != null -> { look = Look.MAIN; capIcon?.kind = CapIcon.RING; capIcon?.frac = if (tot > 0) dn.toFloat() / tot else 0f; text = "${h}点 · ${cur.title}"; chip = "$dn/$tot" }
            inHour.isNotEmpty() && dn == tot -> { look = Look.SOFT; capIcon?.kind = CapIcon.CHECK; text = "${h}点 · 都做完了"; chip = "贴纸 +1" }
            else -> {
                val nx = segs.filter { Engine.live(st, it) && Engine.effMillis(key, st, it) > now }.minByOrNull { Engine.eff(st, it) }
                look = Look.SOFT; capIcon?.kind = CapIcon.MOON
                text = if (nx != null) "下一件：${nx.title}" else "今天结束了"; chip = if (nx != null) Day.fmt(Engine.eff(st, nx)) else ""
            }
        }
        capText?.text = text
        capChip?.text = chip
        capChip?.visibility = if (chip.isEmpty()) View.GONE else View.VISIBLE
        val bg = when (look) { Look.WAIT -> C.RED; Look.MAIN -> C.MAIN; Look.SOFT -> C.SOFT }
        val fg = if (look == Look.SOFT) C.SOFT_INK else Color.WHITE
        capsule?.background = Ui.round(bg, 999, this)
        capText?.setTextColor(fg)
        capChip?.setTextColor(fg)
        capChip?.background = Ui.round(if (look == Look.SOFT) Color.argb(150, 255, 255, 255) else Color.argb(56, 255, 255, 255), 999, this)
        capIcon?.color = fg
        capIcon?.invalidate()
        setPulse(look == Look.WAIT)

        // 锁屏 / 通知栏
        val lines = inHour.flatMap { s -> s.items.map { (if (Engine.isDone(st, s, it)) "✓ " else "○ ") + it.text } }
        goForeground(text, "${Day.fmt(hour)} – ${Day.fmt(hour + 60)}" + if (tot > 0) " · $dn/$tot" else "", lines)

        // 展开的清单
        val pan = panel ?: return
        if (!expanded) { pan.visibility = View.GONE; return }
        pan.removeAllViews()
        pan.addView(Ui.t(this, "${Day.fmt(hour)} – ${Day.fmt(hour + 60)}", 13f, C.MAIN_INK))
        val todo = Engine.todo(key, st, segs)
        val app = pulledFor
        if (app != null && todo != null) {
            pan.addView(Ui.vbox(this).apply {
                background = Ui.round(C.SOFT, 14, this@FloatService)
                setPadding(d(12), d(8), d(12), d(8))
                addView(Ui.h(this@FloatService, "你打开了$app。现在是：$todo", 20f, C.SOFT_INK))
                addView(Ui.link(this@FloatService, "回去做", C.MAIN, 21f) { goHome() })
            }, Ui.lp().apply { topMargin = d(8) })
        }
        val list = inHour.toMutableList()
        listOfNotNull(esc, snz).forEach { if (!list.contains(it)) list.add(0, it) }
        val scroll = ScrollView(this)
        scroll.addView(if (list.isEmpty()) Ui.h(this, "这一小时没有要做的事", 20f, C.QUIET) else Ui.checklist(this, key, list))
        pan.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(6) })
        val bar = Ui.hbox(this).apply { setPadding(0, d(4), 0, 0) }
        val target = esc ?: cur
        if (target != null && !Engine.segDone(st, target)) {
            bar.addView(Ui.link(this, "推迟10分", C.MAIN, 19f) { toast(Engine.snooze(this, key, 10)) })
            bar.addView(Ui.link(this, "推迟30分", C.MAIN, 19f) { toast(Engine.snooze(this, key, 30)) })
        }
        if (inHour.any { !Engine.segDone(st, it) }) bar.addView(Ui.link(this, "重新来", C.QUIET, 19f) {
            Engine.restartHour(this, key); expanded = false; toast("没关系，下一件事重新开始"); refresh()
        })
        bar.addView(View(this), Ui.lp(0, weight = 1f))
        bar.addView(Ui.link(this, "收起", C.QUIET, 19f) { expanded = false; refresh() })
        pan.addView(bar, Ui.lp())
        pan.visibility = View.VISIBLE
    }

    private fun setPulse(on: Boolean) {
        val c = capsule ?: return
        if (on && pulse == null) {
            pulse = ObjectAnimator.ofFloat(c, "alpha", 1f, 0.72f).apply {
                duration = 800; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE; start()
            }
        } else if (!on && pulse != null) {
            pulse?.cancel(); pulse = null; c.alpha = 1f
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun goHome() {
        pulledFor = null; sessionPkg = null; interrupted = false
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        refresh()
    }

    // ---------- 专注锁：盖住整个屏幕 25 分钟 ----------
    private fun updateLock() {
        val st = Store(this).state(Day.key())
        val now = System.currentTimeMillis()
        val am = getSystemService(AudioManager::class.java)
        val inCall = am != null && (am.mode == AudioManager.MODE_RINGTONE || am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION)
        val want = st.focusUntil > now && !inCall && now > suspendLockUntil && Settings.canDrawOverlays(this)
        if (want) {
            if (lockView == null) {
                lockView = buildLock()
                try { wm.addView(lockView, overlayParams(true)) } catch (_: Exception) { lockView = null }
            } else {
                (lockView?.findViewWithTag<TextView>("left"))?.text = "${((st.focusUntil - now) / 60_000L) + 1}"
            }
        } else if (lockView != null) {
            try { wm.removeView(lockView) } catch (_: Exception) {}
            lockView = null
        }
    }

    private fun buildLock(): View {
        val key = Day.key()
        val store = Store(this)
        val st = store.state(key)
        val segs = store.segs(key)
        val now = System.currentTimeMillis()
        val v = Ui.vbox(this).apply {
            setBackgroundColor(C.PAPER)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(d(26), d(80), d(26), d(40))
            isClickable = true
        }
        v.addView(Ui.h(this, "专注中", 24f, C.MAIN), Ui.wrap())
        v.addView(Ui.t(this, "手机已锁 · 来电可以接", 13f), Ui.wrap())
        v.addView(Ui.h(this, st.focusTask, 44f, C.INK).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, d(26), 0, d(20)) }, Ui.wrap())
        v.addView(Ui.h(this, "${((st.focusUntil - now) / 60_000L) + 1}", 96f, C.INK).apply { tag = "left" }, Ui.wrap())
        v.addView(Ui.h(this, "分钟后解锁", 24f, C.QUIET), Ui.wrap())
        v.addView(Ui.h(this, "坚持 25 分钟，${Day.clock(st.focusUntil)} 自动解锁", 22f, C.QUIET).apply { setPadding(0, d(24), 0, 0) }, Ui.wrap())
        v.addView(View(this), Ui.lp(h = 0, weight = 1f))
        // 提前做完：先让开屏幕去拍结果
        val work = segs.firstOrNull { s -> s.items.any { it.kind == Kind.WORK && it.text == st.focusTask } }
        if (work != null) {
            v.addView(Ui.link(this, "提前做完了 · 拍结果", C.MAIN, 22f) {
                suspendLockUntil = System.currentTimeMillis() + 3 * 60_000L
                updateLock()
                Ui.takePhoto(this, key, work, work.items.first { it.text == st.focusTask }, "finish")
            }, Ui.wrap())
        }
        v.addView(Ui.t(this, "紧急情况，先解锁（会记下来）", 13f).apply {
            setPadding(d(10), d(18), d(10), d(10))
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener { Engine.emergencyUnlock(this@FloatService); updateLock(); refresh() }
        }, Ui.wrap())
        return v
    }

    // ---------- 走神：打开抖音 / 微信 ----------
    private fun usageAllowed(): Boolean {
        val ops = getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        else ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun checkUsage() {
        if (!usageAllowed()) return
        val um = getSystemService(UsageStatsManager::class.java) ?: return
        val now = System.currentTimeMillis()
        try {
            val ev = um.queryEvents(now - 8_000, now)
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                if (e.eventType == 1) fgPkg = e.packageName // 1 = 切到前台（ACTIVITY_RESUMED）
            }
        } catch (_: Exception) {
            return
        }
        val app = Store.DISTRACT.firstOrNull { it.pkg == fgPkg }
        if (app == null) {
            if (sessionPkg != null) { sessionPkg = null; pulledFor = null; interrupted = false; refresh() }
            return
        }
        if (sessionPkg != app.pkg) { sessionPkg = app.pkg; sessionStart = now; interrupted = false; pulledFor = null }
        val key = Day.key()
        val store = Store(this)
        val st = store.state(key)
        val todo = Engine.todo(key, st, store.segs(key))
        if (todo != null && pulledFor == null) { pulledFor = app.name; expanded = true; refresh() }
        val limit = store.limit(app.pkg) * 60_000L
        if (!interrupted && now - sessionStart >= limit) {
            interrupted = true
            startActivity(Intent(this, InterruptActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("app", app.name).putExtra("min", ((now - sessionStart) / 60_000L).toInt()).putExtra("todo", todo ?: ""))
        }
    }

    companion object {
        const val ACTION_EXPAND = "jhxt.expand"
        const val ACTION_COLLAPSE = "jhxt.collapse"
        const val ACTION_REFRESH = "jhxt.refresh"
        @Volatile var suspendLockUntil = 0L

        fun start(ctx: Context) = send(ctx, null)
        fun expand(ctx: Context) = send(ctx, ACTION_EXPAND)
        fun refresh(ctx: Context) = send(ctx, ACTION_REFRESH)

        private fun send(ctx: Context, action: String?) {
            try {
                ctx.startForegroundService(Intent(ctx, FloatService::class.java).setAction(action))
            } catch (_: Exception) {
            }
        }
    }
}

// ===================== Screens.kt =====================
/** 透明的中转页：打开系统相机，拍完记进当天的记录。 */
class PhotoActivity : Activity() {
    private var path: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        path = savedInstanceState?.getString("path")
        if (path != null) return
        val dir = File(filesDir, "photos").apply { mkdirs() }
        val file = File(dir, "${System.currentTimeMillis()}.jpg")
        path = file.absolutePath
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val cam = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(cam, 1)
        } catch (e: Exception) {
            Toast.makeText(this, "打不开相机", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("path", path)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val f = path?.let { File(it) }
        FloatService.suspendLockUntil = 0L
        if (resultCode == RESULT_OK && f != null && f.exists() && f.length() > 0) {
            Engine.act(this, intent.getStringExtra("key") ?: Day.key(), intent.getStringExtra("seg") ?: "",
                intent.getStringExtra("item") ?: "", intent.getStringExtra("kind") ?: "photo", f.absolutePath)
        } else {
            f?.delete()
        }
        finish()
    }
}

/** 第3级：全屏，铃声一直响，直到做了其中一项或者推迟。 */
class ReminderActivity : Activity() {
    private var ring: Ringtone? = null
    private lateinit var body: LinearLayout
    private val onChange: () -> Unit = { runOnUiThread { check() } }
    private fun d(v: Number) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true); setTurnScreenOn(true)
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = C.WARM
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        val page = Ui.vbox(this).apply { setBackgroundColor(C.WARM); setPadding(d(26), d(40), d(26), d(30)) }
        page.addView(Ui.hbox(this).apply {
            addView(CapIcon(this@ReminderActivity).apply { kind = CapIcon.BELL; color = C.RED }, LinearLayout.LayoutParams(d(28), d(28)))
            addView(Ui.h(this@ReminderActivity, "  铃声一直在响", 24f, C.RED))
        }, Ui.lp())
        body = Ui.vbox(this)
        page.addView(ScrollView(this).apply { addView(body) }, Ui.lp(h = 0, weight = 1f).apply { topMargin = d(18) })
        page.addView(Ui.hbox(this).apply {
            gravity = Gravity.CENTER
            addView(Ui.link(this@ReminderActivity, "推迟 10 分钟", C.RED, 26f) { snooze(10) })
            addView(View(this@ReminderActivity), LinearLayout.LayoutParams(d(30), 1))
            addView(Ui.link(this@ReminderActivity, "推迟 30 分钟", C.RED, 26f) { snooze(30) })
        }, Ui.lp())
        page.addView(Ui.t(this, "推迟的话，后面的安排一起往后挪；睡觉这类固定的事不动", 13f).apply { gravity = Gravity.CENTER; setPadding(0, d(10), 0, 0) }, Ui.lp())
        setContentView(page)
        render()
        startRing()
        Store.listeners.add(onChange)
    }

    private fun target(): Pair<String, Seg>? {
        val key = Day.key()
        val store = Store(this)
        val segs = store.segs(key)
        val st = store.state(key)
        val s = Engine.escSeg(st, segs) ?: Engine.current(key, st, segs) ?: return null
        return key to s
    }

    private fun render() {
        body.removeAllViews()
        val (key, s) = target() ?: run { finish(); return }
        val st = Store(this).state(key)
        body.addView(Ui.h(this, s.title, 64f), Ui.wrap())
        val late = ((System.currentTimeMillis() - Engine.effMillis(key, st, s)) / 60_000L).coerceAtLeast(0)
        body.addView(Ui.h(this, "已经过了 $late 分钟", 25f, C.RED).apply { setPadding(0, d(8), 0, d(2)) }, Ui.wrap())
        body.addView(Squiggle(this, C.RED, 0.6f), LinearLayout.LayoutParams(d(150), d(8)).apply { bottomMargin = d(26) })
        body.addView(Ui.checklist(this, key, listOf(s), big = true), Ui.lp())
        body.addView(Ui.h(this, "做了其中任意一项，铃声就停。", 22f, C.QUIET).apply { setPadding(0, d(18), 0, 0) }, Ui.wrap())
    }

    private fun check() {
        val t = target()
        if (t == null) { stopRing(); finish(); return }
        val st = Store(this).state(t.first)
        if (Engine.acted(st, t.second)) { stopRing(); finish() } else render()
    }

    private fun snooze(min: Int) {
        Toast.makeText(this, Engine.snooze(this, Day.key(), min), Toast.LENGTH_LONG).show()
        stopRing(); finish()
    }

    private fun startRing() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ring = RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        } catch (_: Exception) {
        }
        Alert.vibrate(this)
    }

    private fun stopRing() {
        try { ring?.stop() } catch (_: Exception) {}
        ring = null
    }

    override fun onDestroy() {
        Store.listeners.remove(onChange)
        stopRing()
        super.onDestroy()
    }
}

/** 分心 APP 用超时了：全屏打断一下。 */
class InterruptActivity : Activity() {
    private fun d(v: Number) = Ui.dp(this, v)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C.PAPER
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        val app = intent.getStringExtra("app") ?: "手机"
        val min = intent.getIntExtra("min", 0)
        val todo = intent.getStringExtra("todo") ?: ""
        val page = Ui.vbox(this).apply { setBackgroundColor(C.PAPER); gravity = Gravity.CENTER_HORIZONTAL; setPadding(d(28), d(110), d(28), d(40)) }
        page.addView(Ui.h(this, "打断一下", 24f, C.RED), Ui.wrap())
        page.addView(Ui.h(this, "$app\n已经 $min 分钟了", 50f).apply { gravity = Gravity.CENTER; setPadding(0, d(16), 0, d(24)) }, Ui.wrap())
        if (todo.isNotEmpty()) {
            page.addView(Ui.h(this, "现在该做的是：", 22f, C.QUIET), Ui.wrap())
            page.addView(Ui.h(this, todo, 32f).apply { setPadding(0, d(6), 0, 0) }, Ui.wrap())
        } else {
            page.addView(Ui.h(this, "休息够了，放下手机吧。", 26f, C.QUIET), Ui.wrap())
        }
        page.addView(View(this), Ui.lp(h = 0, weight = 1f))
        page.addView(Ui.link(this, "关掉，回去做", C.MAIN, 28f) {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }, Ui.wrap())
        setContentView(page)
    }
}

// ===================== MainActivity.kt =====================
/** APP 本身的四页：今天 · 拼贴 · 记录 · 设置。手账的样子。 */
class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var nav: LinearLayout
    private var tab = "today"
    private var logDay = Day.key()
    private val onChange: () -> Unit = { runOnUiThread { render() } }
    private fun d(v: Number) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        Alert.channels(this)
        window.statusBarColor = C.PAPER
        window.navigationBarColor = C.PAPER
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        val page = Ui.vbox(this).apply { setBackgroundColor(C.PAPER) }
        scroll = ScrollView(this)
        content = Ui.vbox(this).apply { setPadding(d(26), d(26), d(26), d(30)) }
        scroll.addView(content)
        page.addView(scroll, Ui.lp(h = 0, weight = 1f))
        nav = Ui.hbox(this).apply { setPadding(0, d(6), 0, d(14)) }
        page.addView(nav, Ui.lp())
        setContentView(page)
        Store.listeners.add(onChange)
    }

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this)) FloatService.start(this)
        Scheduler.reschedule(this)
        render()
    }

    override fun onDestroy() {
        Store.listeners.remove(onChange)
        super.onDestroy()
    }

    private fun render() {
        nav.removeAllViews()
        listOf("today" to "今天", "collage" to "拼贴", "log" to "记录", "set" to "设置").forEach { (id, name) ->
            val on = id == tab
            val cell = Ui.vbox(this).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                minimumHeight = d(48)
                isClickable = true
                contentDescription = name
                setOnClickListener { tab = id; if (id == "log") logDay = Day.key(); render(); scroll.scrollTo(0, 0) }
                addView(Ui.h(this@MainActivity, name, 24f, if (on) C.INK else C.QUIET), Ui.wrap())
                addView(if (on) Squiggle(this@MainActivity, C.DOT, 1f) else View(this@MainActivity), LinearLayout.LayoutParams(d(44), d(8)))
            }
            nav.addView(cell, Ui.lp(0, weight = 1f))
        }
        content.removeAllViews()
        when (tab) {
            "today" -> today()
            "collage" -> collage()
            "log" -> record()
            else -> settings()
        }
    }

    private fun header(title: String, date: String, sticker: Int) {
        val row = Ui.hbox(this).apply { gravity = Gravity.BOTTOM }
        row.addView(Ui.h(this, title, 58f), Ui.wrap())
        row.addView(Ui.h(this, "  $date", 21f, C.QUIET).apply { setPadding(0, 0, 0, d(6)) }, Ui.lp(0, weight = 1f))
        row.addView(Sticker(this, sticker).apply { rotation = 8f }, LinearLayout.LayoutParams(d(40), d(40)))
        content.addView(row, Ui.lp().apply { bottomMargin = d(22) })
    }

    private fun gap(dp: Int) = content.addView(View(this), LinearLayout.LayoutParams(1, d(dp)))

    // ---------- 今天 ----------
    private fun today() {
        val key = Day.key()
        val segs = store.segs(key)
        val st = store.state(key)
        header("今天", Day.label(key), 0)
        val miss = missing()
        if (miss.isNotEmpty()) {
            content.addView(Ui.link(this, "还有 ${miss.size} 项权限没开，提醒可能被拦掉 →", C.RED, 19f) { tab = "set"; render() }.apply {
                (this as LinearLayout).gravity = Gravity.START
            }, Ui.lp().apply { bottomMargin = d(16) })
        }
        if (segs.isEmpty()) { content.addView(Ui.h(this, "还没有安排。去「设置」里写一下吧。", 22f, C.QUIET)); return }
        val now = System.currentTimeMillis()
        val nm = Day.minOf(key, now)
        val cur = Engine.current(key, st, segs)
        val hours = segs.groupBy { Day.hourOf(Engine.eff(st, it)) }.toSortedMap()
        hours.forEach { (hour, list) ->
            list.sortedBy { Engine.eff(st, it) }.forEachIndexed { i, s ->
                val live = Engine.live(st, s)
                val done = Engine.segDone(st, s)
                val isCur = s == cur
                val row = Ui.hbox(this).apply { minimumHeight = d(44) }
                val time = Ui.h(this, Day.fmt(Engine.eff(st, s)), if (isCur) 25f else 21f, if (isCur) C.INK else C.QUIET).apply {
                    if (isCur) { background = Ui.round(C.SOFT, 14, this@MainActivity); setPadding(d(4), d(2), d(4), d(2)) }
                }
                row.addView(time, LinearLayout.LayoutParams(d(66), -2))
                val mark = when { done -> Mark.CHECK; isCur || (Engine.eff(st, s) <= nm && live) -> Mark.CIRCLE; else -> Mark.DOT }
                row.addView(Mark(this, mark), LinearLayout.LayoutParams(d(24), d(24)).apply { marginEnd = d(8) })
                row.addView(Ui.h(this, s.title, 26f, if (done || !live || Engine.eff(st, s) > nm) C.QUIET else C.INK).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                }, Ui.lp(0, weight = 1f))
                val off = st.offset[s.id] ?: 0
                when {
                    !live -> row.addView(Ui.h(this, "放下了", 18f, C.QUIET))
                    off > 0 -> row.addView(Ui.h(this, "推迟 $off 分", 18f, C.RED))
                    s.fixed -> row.addView(Ui.h(this, "不挪", 18f, C.QUIET))
                }
                if (i == list.size - 1 && st.stickers.contains(hour)) {
                    row.addView(Sticker(this, st.stickers.indexOf(hour)).apply { rotation = 10f }, LinearLayout.LayoutParams(d(30), d(30)).apply { marginStart = d(6) })
                }
                content.addView(row, Ui.lp())
                if (isCur) {
                    content.addView(Ui.checklist(this, key, listOf(s), showTime = false, heads = false).apply {
                        setPadding(d(66), 0, 0, d(4))
                    }, Ui.lp())
                }
            }
            gap(22)
        }
    }

    // ---------- 拼贴 ----------
    private fun collage() {
        val key = Day.key()
        val st = store.state(key)
        header("拼贴", Day.label(key), 4)
        content.addView(Ui.h(this, "做完一个小时，就贴一张。", 22f, C.QUIET), Ui.lp().apply { bottomMargin = d(20) })
        if (st.stickers.isEmpty()) {
            content.addView(Ui.h(this, "今天还没有贴纸。\n做完一个小时，这里就会多一张。", 24f, C.QUIET).apply { gravity = Gravity.CENTER; setPadding(0, d(60), 0, 0) }, Ui.lp())
            return
        }
        val grid = GridLayout(this).apply { columnCount = 3 }
        val rots = floatArrayOf(-6f, 4f, -3f, 7f, -5f, 3f)
        st.stickers.forEachIndexed { i, h ->
            val cell = Ui.vbox(this).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                rotation = rots[i % rots.size]
                setPadding(0, d(10), 0, d(18))
                addView(Sticker(this@MainActivity, i), LinearLayout.LayoutParams(d(78), d(78)))
                addView(Ui.h(this@MainActivity, "${(h / 60) % 24}点", 22f, C.MAIN_INK), Ui.wrap())
            }
            grid.addView(cell, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0 })
        }
        content.addView(grid, Ui.lp())
        content.addView(Ui.h(this, "今天贴了 ${st.stickers.size} 张", 24f).apply { gravity = Gravity.CENTER; setPadding(0, d(16), 0, 0) }, Ui.lp())
    }

    // ---------- 记录 ----------
    private fun record() {
        header("记录", Day.label(logDay), 1)
        val navRow = Ui.hbox(this)
        navRow.addView(Ui.link(this, "‹ 前一天", C.MAIN, 20f) { logDay = Day.shift(logDay, -1); render() })
        navRow.addView(View(this), Ui.lp(0, weight = 1f))
        if (logDay != Day.key()) navRow.addView(Ui.link(this, "后一天 ›", C.MAIN, 20f) { logDay = Day.shift(logDay, 1); render() })
        content.addView(navRow, Ui.lp().apply { bottomMargin = d(10) })
        val segs = store.segs(logDay)
        val st = store.state(logDay)

        content.addView(Ui.section(this, "几点开始的"), Ui.lp().apply { bottomMargin = d(8) })
        val started = segs.filter { st.actual.containsKey(it.id) }.sortedBy { st.actual[it.id] }
        if (started.isEmpty()) content.addView(Ui.h(this, "这一天还没有开始做的事", 21f, C.QUIET))
        started.forEach { s ->
            val at = st.actual[s.id]!!
            val late = Day.minOf(logDay, at) - s.min
            val row = Ui.hbox(this).apply { minimumHeight = d(52) }
            val left = Ui.vbox(this)
            left.addView(Ui.h(this, Day.clock(at), 25f), Ui.wrap())
            left.addView(Ui.t(this, if (late > 0) Day.fmt(s.min) else " ", 12f, C.FAINT).apply {
                if (late > 0) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            }, Ui.wrap())
            row.addView(left, LinearLayout.LayoutParams(d(66), -2))
            row.addView(Ui.h(this, s.title, 25f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, Ui.lp(0, weight = 1f))
            row.addView(Ui.h(this, if (late > 0) "晚 $late 分" else "准时", 19f, if (late > 0) C.RED else C.MAIN))
            content.addView(row, Ui.lp())
        }
        gap(26)
        content.addView(Ui.section(this, "每一步"), Ui.lp().apply { bottomMargin = d(8) })
        val list = st.entries.sortedBy { it.at }
        if (list.isEmpty()) content.addView(Ui.h(this, "还没有记录", 21f, C.QUIET))
        list.forEach { e ->
            val row = Ui.hbox(this).apply { minimumHeight = d(40) }
            row.addView(Ui.h(this, Day.clock(e.at), 21f, C.QUIET), LinearLayout.LayoutParams(d(66), -2))
            val label = when (e.kind) { "emergency" -> "紧急解锁：${e.item}"; "start" -> "开始：${e.item}"; "finish" -> "完成：${e.item}"; else -> e.item }
            row.addView(Ui.h(this, label, 23f, if (e.kind == "emergency") C.RED else C.INK).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, Ui.lp(0, weight = 1f))
            e.photo?.let { p -> thumb(p)?.let { bmp ->
                row.addView(ImageView(this).apply {
                    setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Color.WHITE); setPadding(d(3), d(3), d(3), d(9)); rotation = -3f; elevation = d(1).toFloat()
                }, LinearLayout.LayoutParams(d(50), d(48)))
            } }
            content.addView(row, Ui.lp())
        }
    }

    private fun thumb(path: String) = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var s = 1
        while (o.outWidth / s > 200 || o.outHeight / s > 200) s *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (_: Exception) { null }

    // ---------- 设置 ----------
    private data class Perm(val name: String, val ok: Boolean, val go: () -> Unit)

    private fun perms(): List<Perm> = listOf(
        Perm("显示在其他应用上层（胶囊、专注锁）", Settings.canDrawOverlays(this)) {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        },
        Perm("通知（锁屏上的待办）", Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        },
        Perm("全屏提醒（第3级铃声）", Build.VERSION.SDK_INT < 34 || getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
            if (Build.VERSION.SDK_INT >= 34) open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
        },
        Perm("使用情况访问（发现你打开抖音、微信）", usageOk()) {
            open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        },
        Perm("后台不限制（不被系统杀掉）", getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
            open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    )

    private fun missing() = perms().filter { !it.ok }

    private fun usageOk(): Boolean {
        val ops = getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        else ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun settings() {
        header("设置", "", 3)
        val miss = missing()
        if (miss.isNotEmpty()) {
            content.addView(Ui.section(this, "还没开的权限"), Ui.lp().apply { bottomMargin = d(6) })
            miss.forEach { p -> content.addView(Ui.link(this, "${p.name} →", C.RED, 20f) { p.go() }.apply { (this as LinearLayout).gravity = Gravity.START }, Ui.lp()) }
            content.addView(Ui.t(this, "荣耀手机还要手动一步：设置 → 应用和服务 → 应用启动管理 → 进化系统 → 关掉「自动管理」，三个开关都打开。", 14f).apply { setPadding(0, d(8), 0, 0) }, Ui.lp())
            content.addView(Ui.link(this, "打开本应用的系统设置 →", C.MAIN, 19f) {
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }.apply { (this as LinearLayout).gravity = Gravity.START }, Ui.lp())
            gap(24)
        }

        content.addView(Ui.section(this, "我的一天"), Ui.lp())
        content.addView(Ui.t(this, "每天都一样的事。点一行改它 · 相机 = 要拍照 · 图钉 = 固定不挪", 14f).apply { setPadding(0, d(4), 0, d(8)) }, Ui.lp())
        val routine = store.routine
        routine.forEachIndexed { i, r ->
            val row = Ui.hbox(this).apply {
                minimumHeight = d(52); isClickable = true
                setOnClickListener { editRoutine(i) }
            }
            row.addView(Ui.h(this, r.time, 21f, C.QUIET), LinearLayout.LayoutParams(d(66), -2))
            val mid = Ui.vbox(this)
            mid.addView(Ui.h(this, r.title, 26f), Ui.wrap())
            val sub = r.items.filter { it != r.title }
            if (sub.isNotEmpty()) mid.addView(Ui.h(this, sub.joinToString(" · "), 18f, C.QUIET), Ui.wrap())
            row.addView(mid, Ui.lp(0, weight = 1f))
            row.addView(toggle(r.photo, "拍照", CapIcon.CAMERA) { store.routine = routine.toMutableList().also { it[i] = r.copy(photo = !r.photo) } })
            row.addView(View(this), LinearLayout.LayoutParams(d(8), 1))
            row.addView(toggle(r.fixed, "固定", CapIcon.PIN) { store.routine = routine.toMutableList().also { it[i] = r.copy(fixed = !r.fixed) } })
            content.addView(row, Ui.lp())
        }
        content.addView(Ui.link(this, "+ 加一件事", C.MAIN, 22f) { editRoutine(-1) }.apply { (this as LinearLayout).gravity = Gravity.START; setPadding(d(66), d(6), 0, 0) }, Ui.lp())
        gap(22)

        content.addView(Ui.section(this, "工作安排"), Ui.lp())
        listOf(0 to "今天的工作", 1 to "明天的工作").forEach { (off, name) ->
            val k = Day.shift(Day.key(), off)
            val n = Plan.parseWork(store.work(k)).size
            val row = Ui.hbox(this).apply { minimumHeight = d(52) }
            val mid = Ui.vbox(this)
            mid.addView(Ui.h(this, name, 26f), Ui.wrap())
            mid.addView(Ui.h(this, if (n == 0) "还没排" else "$n 件", 18f, C.QUIET), Ui.wrap())
            row.addView(mid, Ui.lp(0, weight = 1f))
            row.addView(Ui.link(this, if (n == 0) "粘贴 →" else "修改 →", C.MAIN, 22f) { editWork(k, name) })
            content.addView(row, Ui.lp())
        }
        gap(22)

        content.addView(Ui.section(this, "分心 APP"), Ui.lp())
        content.addView(Ui.t(this, "一次连续用超过这个时间，就打断你", 14f).apply { setPadding(0, d(4), 0, d(6)) }, Ui.lp())
        Store.DISTRACT.forEach { app ->
            val row = Ui.hbox(this).apply { minimumHeight = d(50) }
            row.addView(Ui.h(this, app.name, 26f), Ui.lp(0, weight = 1f))
            row.addView(circle("−") { store.setLimit(app.pkg, store.limit(app.pkg) - 1) })
            row.addView(Ui.h(this, "${store.limit(app.pkg)} 分钟", 24f, C.MAIN_INK).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(d(84), -2))
            row.addView(circle("+") { store.setLimit(app.pkg, store.limit(app.pkg) + 1) })
            content.addView(row, Ui.lp())
        }
        gap(26)
        content.addView(Ui.section(this, "试一下"), Ui.lp())
        val tryRow = Ui.hbox(this)
        tryRow.addView(Ui.link(this, "展开胶囊", C.MAIN, 21f) { FloatService.expand(this) })
        tryRow.addView(View(this), LinearLayout.LayoutParams(d(20), 1))
        tryRow.addView(Ui.link(this, "全屏提醒", C.MAIN, 21f) { Alert.fullScreen(this) })
        content.addView(tryRow, Ui.lp())
    }

    private fun toggle(on: Boolean, label: String, icon: Int, flip: () -> Unit): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        background = if (on) Ui.round(C.MAIN, 999, this@MainActivity) else Ui.round(Color.TRANSPARENT, 999, this@MainActivity, C.CIRCLE)
        contentDescription = label + if (on) "（开）" else "（关）"
        isClickable = true
        setOnClickListener { flip() }
        addView(CapIcon(this@MainActivity).apply { kind = icon; color = if (on) Color.WHITE else C.CIRCLE }, LinearLayout.LayoutParams(d(20), d(20)))
        layoutParams = LinearLayout.LayoutParams(d(36), d(36))
    }

    private fun circle(s: String, onClick: () -> Unit): View = TextView(this).apply {
        text = s; textSize = 18f; setTextColor(C.MAIN); gravity = Gravity.CENTER
        background = Ui.round(Color.TRANSPARENT, 999, this@MainActivity, C.CIRCLE)
        contentDescription = if (s == "+") "增加" else "减少"
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(d(36), d(36))
    }

    private fun field(hint: String, value: String, multi: Boolean = false) = EditText(this).apply {
        setText(value); this.hint = hint; textSize = 17f
        if (multi) { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 3; gravity = Gravity.TOP or Gravity.START }
    }

    private fun editRoutine(i: Int) {
        val list = store.routine.toMutableList()
        val r = list.getOrNull(i)
        val time = field("时间，比如 08:00", r?.time ?: "")
        val title = field("叫什么，比如 起床", r?.title ?: "")
        val items = field("要做的事，一行一件", r?.items?.joinToString("\n") ?: "", multi = true)
        val box = Ui.vbox(this).apply { setPadding(d(22), d(10), d(22), 0); addView(time); addView(title); addView(items) }
        val dlg = AlertDialog.Builder(this).setTitle(if (r == null) "加一件事" else "改一改").setView(box)
            .setPositiveButton("保存") { _, _ ->
                val t = time.text.toString().trim().replace("：", ":")
                val min = Day.parseTime(t)
                val name = title.text.toString().trim()
                if (min == null || name.isEmpty()) { Toast.makeText(this, "时间要写成 08:00 这样，名字不能空", Toast.LENGTH_LONG).show(); return@setPositiveButton }
                val e = RoutineEntry(Day.fmt(min), name, items.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() },
                    r?.photo ?: false, r?.fixed ?: false)
                if (r == null) list.add(e) else list[i] = e
                store.routine = list
            }
            .setNegativeButton("取消", null)
        if (r != null) dlg.setNeutralButton("删掉") { _, _ -> list.removeAt(i); store.routine = list }
        dlg.show()
    }

    private fun editWork(key: String, name: String) {
        val f = field("10:30 写开题报告第2节 | 最低：写3句话\n14:00 整理教学大赛材料", store.work(key), multi = true)
        val box = Ui.vbox(this).apply {
            setPadding(d(22), d(10), d(22), 0)
            addView(Ui.t(this@MainActivity, "把晚上和 Claude 排好的粘贴进来，一行一件。", 14f))
            addView(f)
        }
        AlertDialog.Builder(this).setTitle(name).setView(box)
            .setPositiveButton("保存") { _, _ -> store.setWork(key, f.text.toString()); Scheduler.reschedule(this) }
            .setNegativeButton("取消", null).show()
    }

    private fun open(i: Intent) {
        try { startActivity(i) } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }
}
