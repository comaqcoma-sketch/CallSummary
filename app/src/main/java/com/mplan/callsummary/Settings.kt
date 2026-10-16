package com.mplan.callsummary

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme: ColorScheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun ChoiceRow(selected: Boolean, title: String, desc: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(top = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(desc, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 변환 모델 + 언어 + 화자 구분 + 요약 방식 설정 */
@Composable
fun SetupDialog(confirmLabel: String, onDone: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var model by remember { mutableStateOf(ModelManager.selected(ctx) ?: ModelManager.recommended(ctx)) }
    var words by remember { mutableStateOf(ModelManager.words(ctx)) }
    var speakers by remember { mutableIntStateOf(ModelManager.speakers(ctx)) }
    var ai by remember { mutableStateOf(ModelManager.aiSummary(ctx) ?: true) }
    var lang by remember { mutableStateOf(ModelManager.lang(ctx)) }
    val ramGb = remember { Device.totalRamGb(ctx) }
    val localOk = remember { Device.canRunLocalLlm(ctx) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("변환·요약 설정") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("1. 음성 → 텍스트", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                WhisperModel.entries.forEach { m ->
                    val rec = if (m == ModelManager.recommended(ctx)) " · 이 폰 권장" else ""
                    ChoiceRow(model == m, "${m.label} · ${m.sizeMb}MB$rec", m.desc) { model = m }
                }
                OutlinedTextField(
                    value = words,
                    onValueChange = { words = it.take(200) },
                    label = { Text("자주 나오는 단어 (선택)") },
                    placeholder = { Text("예: 홍길동, 라이노, 견적서") },
                    supportingText = { Text("사람 이름·회사명·전문용어를 쉼표로 적으면 더 정확히 알아듣습니다") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text("2. 통화 언어", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                SpeechLang.entries.forEach { l ->
                    ChoiceRow(lang == l, l.label, l.desc) { lang = l }
                }
                Spacer(Modifier.height(12.dp))
                Text("3. 화자 구분", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                ChoiceRow(speakers >= 0, "켜기 (A: … B: …)", "목소리로 통화한 두 사람을 나눔 · 최초 1회 ${SpeakerModel.SIZE_MB}MB") { speakers = 2 }
                ChoiceRow(speakers < 0, "끄기", "화자 구분 없이 텍스트만") { speakers = -1 }
                Spacer(Modifier.height(12.dp))
                Text("4. 요약 방식", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                ChoiceRow(ai, "AI 요약 (권장)",
                    if (localOk) "지원 폰은 Gemini Nano 사용(추가 다운로드 없음), 그 외 폰은 내장 AI ${LocalLlm.SIZE_MB}MB를 처음 한 번 받음"
                    else "Gemini Nano 지원 폰에서만 AI 요약. 이 폰은 메모리(${"%.1f".format(ramGb)}GB)가 작아 내장 AI 대신 기본 요약으로 자동 전환") { ai = true }
                ChoiceRow(!ai, "기본 요약", "AI 없이 중요한 문장만 골라 보여줌 · 추가 다운로드 없음") { ai = false }
                Spacer(Modifier.height(12.dp))
                Text(
                    "모든 처리는 폰 안에서 이루어지며 녹음과 텍스트는 외부로 전송되지 않습니다. " +
                        "모델 다운로드는 처음 한 번만 하며 Wi-Fi를 권장합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                ModelManager.select(ctx, model)
                ModelManager.setAiSummary(ctx, ai)
                ModelManager.setLang(ctx, lang)
                ModelManager.setWords(ctx, words)
                ModelManager.setSpeakers(ctx, speakers)
                onDone()
            }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

