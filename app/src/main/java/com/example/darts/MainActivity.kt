package com.example.darts

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
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

const val THROWS_PER_ROUND = 5
const val TARGET_SCORE = 40

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Если прошлый запуск упал, показываем текст ошибки на экране
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

        setContent {
            MaterialTheme {
                DartsGame(onGameOver = { win ->
                    // СЮДА потом подключим показ рекламы после поражения
                })
            }
        }
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

fun pointsFor(dist: Float): Int = when {
    dist < 0.10f -> 50
    dist < 0.30f -> 25
    dist < 0.55f -> 10
    dist < 0.85f -> 5
    else -> 0
}

@Composable
fun DartsGame(onGameOver: (Boolean) -> Unit) {
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

    val aim = Offset(sin(time * 1.7f) * 0.9f, sin(time * 2.3f + 1f) * 0.9f)

    fun throwDart() {
        if (over) return
        darts = darts + aim
        score += pointsFor(hypot(aim.x, aim.y))
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
        Text("Очки: $score / $TARGET_SCORE", fontSize = 24.sp)
        Text("Бросков осталось: $throwsLeft", fontSize = 16.sp)
        Spacer(Modifier.height(12.dp))

        Canvas(
            Modifier.weight(1f).fillMaxWidth()
                .pointerInput(over) { detectTapGestures { throwDart() } }
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
            Button(onClick = { restart() }) { Text("Играть снова") }
        } else {
            Text("Тапни по экрану, чтобы бросить дротик", fontSize = 14.sp)
        }
    }
}
