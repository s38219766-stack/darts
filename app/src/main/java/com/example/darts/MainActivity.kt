package com.example.darts

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

const val THROWS_PER_ROUND = 5
const val TARGET_SCORE = 40

class MainActivity : ComponentActivity() {
    private val api by lazy { Api(applicationContext) }
    private val ads by lazy { Ads(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("crash", MODE_PRIVATE)
        val old = prefs.getString("crash", null)
        if (old != null) {
            showCrash(old, prefs)
            return
        }

        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            prefs.edit().putString("crash", Log.getStackTraceString(e)).commit()
            prev?.uncaughtException(t, e)
        }

        ads.init()
        setContent { MaterialTheme { App(api, ads, this@MainActivity) } }
    }

    private fun showCrash(text: String, prefs: SharedPreferences) {
        setContent {
            MaterialTheme {
                Column(
                    Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())
                ) {
                    Text("Приложение упало. Сделай скриншот этого экрана и пришли мне:", fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer { Text(text.take(2500), fontSize = 11.sp) }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        prefs.edit().remove("crash").commit()
                        finish()
                    }) { Text("Очистить и закрыть") }
                }
            }
        }
    }
}

@Composable
fun App(api: Api, ads: Ads, activity: Activity) {
    var screen by remember { mutableStateOf("game") }
    var profile by remember { mutableStateOf<Profile?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var watching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun refresh(): Boolean = try {
        profile = api.getProfile()
        true
    } catch (e: Exception) {
        false
    }
    LaunchedEffect(Unit) { refresh() }

    // Реклама за награду, затем ждём, пока сервер получит подтверждение от Unity
    fun watchForReward() {
        if (watching) return
        watching = true
        status = null
        scope.launch {
            val uid = try {
                api.ensureSession().userId
            } catch (e: Exception) {
                status = "Нет связи с сервером"
                watching = false
                return@launch
            }
            val before = profile?.balance ?: 0L
            val started = ads.showRewarded(activity, uid) { completed ->
                scope.launch {
                    if (!completed) {
                        status = "Реклама не досмотрена"
                        watching = false
                        return@launch
                    }
                    status = "Просмотр засчитан, ждём начисления…"
                    var credited = false
                    repeat(10) {
                        if (!credited) {
                            delay(3000)
                            refresh()
                            if ((profile?.balance ?: 0L) > before) credited = true
                        }
                    }
                    status = if (credited) "Баланс пополнен!"
                    else "Начисление ещё не пришло. Загляните в баланс чуть позже."
                    watching = false
                }
            }
            if (!started) {
                status = "Реклама ещё загружается, попробуйте через несколько секунд"
                watching = false
            }
        }
    }

    if (screen == "game") {
        DartsGame(
            promoActive = profile?.promoActive == true,
            rewardReady = ads.rewardedReady,
            watching = watching,
            status = status,
            onGameOver = { win ->
                // Обычным игрокам после поражения показываем рекламу сразу
                if (!win && profile?.promoActive != true) ads.showInterstitial(activity)
            },
            onWatchReward = { watchForReward() },
            onOpenBalance = { screen = "balance" }
        )
    } else {
        BalanceScreen(
            api = api,
            profile = profile,
            onRefresh = { refresh() },
            rewardReady = ads.rewardedReady,
            watching = watching,
            status = status,
            onWatchReward = { watchForReward() },
            onBack = { screen = "game" }
        )
    }
}

@Composable
fun BalanceScreen(
    api: Api,
    profile: Profile?,
    onRefresh: suspend () -> Boolean,
    rewardReady: Boolean,
    watching: Boolean,
    status: String?,
    onWatchReward: () -> Unit,
    onBack: () -> Unit
) {
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        error = if (onRefresh()) null else "Нет связи с сервером"
    }
    LaunchedEffect(Unit) { load() }

    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("← Назад к игре") }
        }
        Spacer(Modifier.height(24.dp))

        val p = profile
        when {
            p == null && error != null -> {
                Text(error!!, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { scope.launch { load() } }) { Text("Повторить") }
            }
            p == null -> CircularProgressIndicator()
            !p.promoActive -> {
                Text("Введите промокод", fontSize = 22.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    singleLine = true,
                    label = { Text("Промокод") }
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    enabled = !busy && code.isNotBlank(),
                    onClick = {
                        scope.launch {
                            busy = true
                            message = null
                            try {
                                if (api.redeemPromo(code)) load()
                                else message = "Неверный или закончившийся промокод"
                            } catch (e: Exception) {
                                message = "Ошибка: ${e.message}"
                            }
                            busy = false
                        }
                    }
                ) { Text("Активировать") }
                message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 14.sp)
                }
            }
            else -> {
                Text("Ваш баланс", fontSize = 18.sp)
                Text("${p.balance}", fontSize = 48.sp)
                Spacer(Modifier.height(16.dp))
                Button(
                    enabled = rewardReady && !watching,
                    onClick = onWatchReward
                ) {
                    Text(if (rewardReady) "▶ Смотреть рекламу и получить баланс" else "Реклама загружается…")
                }
                status?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 14.sp)
                }
            }
        }
    }
}

fun pointsFor(dist: Float): Int = when {
    dist < 0.10f -> 50
    dist < 0.30f -> 25
    dist < 0.55f -> 10
    dist < 0.85f -> 5
    else -> 0
}

@Composable
fun DartsGame(
    promoActive: Boolean,
    rewardReady: Boolean,
    watching: Boolean,
    status: String?,
    onGameOver: (Boolean) -> Unit,
    onWatchReward: () -> Unit,
    onOpenBalance: () -> Unit
) {
    var time by remember { mutableStateOf(0f) }
    var darts by remember { mutableStateOf(listOf<Offset>()) }
    var score by remember { mutableStateOf(0) }
    var throwsLeft by remember { mutableStateOf(THROWS_PER_ROUND) }
    val over = throwsLeft == 0

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) time += (now - last) / 1_000_000_000f
                last = now
            }
        }
    }

    fun aimNow() = Offset(sin(time * 1.7f) * 0.9f, sin(time * 2.3f + 1f) * 0.9f)
    val aim = aimNow()

    fun throwDart() {
        if (throwsLeft == 0) return
        val a = aimNow()
        darts = darts + a
        score += pointsFor(hypot(a.x, a.y))
        throwsLeft -= 1
        if (throwsLeft == 0) onGameOver(score >= TARGET_SCORE)
    }

    fun restart() {
        darts = emptyList(); score = 0; throwsLeft = THROWS_PER_ROUND
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = onOpenBalance) { Text("Баланс") }
        }
        Text("Очки: $score / $TARGET_SCORE", fontSize = 24.sp)
        Text("Бросков осталось: $throwsLeft", fontSize = 16.sp)
        Spacer(Modifier.height(12.dp))

        Canvas(
            Modifier.weight(1f).fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures { throwDart() } }
        ) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = min(size.width, size.height) / 2 * 0.9f
            fun p(o: Offset) = Offset(c.x + o.x * r, c.y + o.y * r)

            listOf(
                1.0f to Color(0xFF263238), 0.85f to Color(0xFFEEEEEE),
                0.55f to Color(0xFF43A047), 0.30f to Color(0xFFE53935),
                0.10f to Color(0xFFFFD600)
            ).forEach { (k, col) -> drawCircle(col, r * k, c) }

            darts.forEach { drawCircle(Color(0xFF1565C0), 10f, p(it)) }

            if (!over) {
                val a = p(aim)
                drawCircle(Color.Black, 22f, a, style = Stroke(4f))
                drawLine(Color.Black, Offset(a.x - 30, a.y), Offset(a.x + 30, a.y), 4f)
                drawLine(Color.Black, Offset(a.x, a.y - 30), Offset(a.x, a.y + 30), 4f)
            }
        }

        if (over) {
            val win = score >= TARGET_SCORE
            Text(if (win) "Победа!" else "Поражение", fontSize = 28.sp)
            if (!win && promoActive) {
                Button(enabled = rewardReady && !watching, onClick = onWatchReward) {
                    Text(if (rewardReady) "▶ Смотреть рекламу и получить баланс" else "Реклама загружается…")
                }
            }
            status?.let { Text(it, fontSize = 14.sp) }
            Button(onClick = { restart() }) { Text("Играть снова") }
        } else {
            Text("Тапни по экрану, чтобы бросить дротик", fontSize = 14.sp)
        }
    }
}
