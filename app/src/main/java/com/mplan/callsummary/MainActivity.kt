package com.mplan.callsummary

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { CallSummaryScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallSummaryScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tr by TranscribeService.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var tree by remember { mutableStateOf(CallStore.treeUri(ctx)) }
    var calls by remember { mutableStateOf<List<CallItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var autoOn by remember { mutableStateOf(CallStore.autoSince(ctx) > 0) }
    var openDetail by remember { mutableStateOf<CallItem?>(null) }
    var askSetupFor by remember { mutableStateOf<CallItem?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<CallItem?>(null) }

    // 앱으로 돌아올 때마다 새 통화 확인
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) reloadKey++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(tree, reloadKey, tr.doneCount) {
        if (tree == null) return@LaunchedEffect
        loading = true
        calls = CallStore.list(ctx)
        loading = false
    }
    LaunchedEffect(tr.error) {
        tr.error?.let { snackbar.showSnackbar(it); TranscribeService.clearError() }
    }
    LaunchedEffect(tr.doneCount) {
        if (tr.doneCount > 0) snackbar.showSnackbar("통화 정리가 완료됐습니다.")
    }

    // 알림 권한 (진행 상황 알림용, 거부해도 동작함)
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun askNotif() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun process(item: CallItem) {
        if (tr.path != null) return
        if (ModelManager.selected(ctx) == null || ModelManager.aiSummary(ctx) == null) { askSetupFor = item; return }
        askNotif()
        scope.launch {
            try {
                val f = CallStore.importCopy(ctx, item)
                TranscribeService.start(ctx, f.absolutePath, title = item.title)
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "통화 녹음 파일을 열 수 없습니다.")
            }
        }
    }

    // 새 통화 자동 정리 (앱이 열려 있을 때 하나씩 차례로)
    LaunchedEffect(calls, tr.path, autoOn) {
        if (!autoOn || tr.path != null) return@LaunchedEffect
        if (ModelManager.selected(ctx) == null || ModelManager.aiSummary(ctx) == null) return@LaunchedEffect
        val since = CallStore.autoSince(ctx)
        calls.filter { it.time >= since && !it.hasText }.minByOrNull { it.time }?.let { process(it) }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            try {
                CallStore.setTree(ctx, uri)
                tree = uri
                reloadKey++
            } catch (_: Exception) {
                scope.launch { snackbar.showSnackbar("이 폴더는 사용할 수 없습니다. 다른 폴더를 골라 주세요.") }
            }
        }
    }

    val detail = openDetail
    if (detail != null) {
        val dur = remember(detail) { CallStore.durationMs(detail) }
        DetailScreen(
            Recording(detail.localPath, detail.title, dur, detail.sizeBytes, detail.hasText),
            onBack = { openDetail = null; reloadKey++ }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("통화 요약") },
                actions = { TextButton(onClick = { showSettings = true }) { Text("설정") } }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (tree == null) {
                item { SetupGuide(onPick = { folderPicker.launch(CallStore.initialFolderUri) }) }
                return@LazyColumn
            }
            item {
                FolderCard(
                    label = CallStore.folderLabel(tree!!),
                    autoOn = autoOn,
                    onAuto = { on -> autoOn = on; CallStore.setAuto(ctx, on); if (on) askNotif() },
                    onChange = { folderPicker.launch(CallStore.initialFolderUri) }
                )
            }
            item {
                Text(
                    if (loading && calls.isEmpty()) "통화 녹음 불러오는 중…" else "통화 녹음 (${calls.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (!loading && calls.isEmpty()) {
                item {
                    Text(
                        "이 폴더에 통화 녹음 파일이 없습니다.\n전화 앱의 통화 녹음이 켜져 있는지, 폴더가 맞는지 확인해 주세요.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp)
                    )
                }
            }
            items(calls, key = { it.docUri.toString() }) { c ->
                CallRow(
                    item = c,
                    working = tr.path == c.localPath,
                    busy = tr.path != null,
                    phase = tr.phase,
                    progress = tr.progress,
                    onProcess = { process(c) },
                    onCancel = { TranscribeService.cancel(ctx) },
                    onOpen = { openDetail = c },
                    onDelete = { toDelete = c }
                )
            }
        }
    }

    askSetupFor?.let { c ->
        SetupDialog(
            confirmLabel = "정리 시작",
            onDone = { askSetupFor = null; process(c) },
            onDismiss = { askSetupFor = null }
        )
    }
    if (showSettings) {
        SetupDialog(confirmLabel = "저장", onDone = { showSettings = false }, onDismiss = { showSettings = false })
    }
    toDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("정리 결과 삭제") },
            text = { Text("이 앱이 만든 텍스트와 요약만 삭제합니다.\n전화 앱의 원본 통화 녹음은 그대로 남습니다.") },
            confirmButton = {
                TextButton(onClick = { CallStore.deleteResult(ctx, c); toDelete = null; reloadKey++ }) {
                    Text("삭제", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("취소") } }
        )
    }
}

@Composable
private fun SetupGuide(onPick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("시작하기", style = MaterialTheme.typography.titleLarge)
            Text(
                "이 앱은 휴대폰 전화 앱이 녹음한 통화를 텍스트로 바꾸고 요약합니다. " +
                    "통화를 직접 녹음하지 않고, 녹음 파일을 읽기만 합니다.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text("1. 전화 앱에서 통화 녹음 켜기", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "갤럭시: 전화 앱 → 오른쪽 위 ⋮ → 설정 → 통화 녹음 → '통화 자동 녹음' 켜기",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("2. 통화 녹음 폴더 선택", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "갤럭시는 보통 내장 저장공간 → Recordings → Call 폴더입니다. 폴더를 연 뒤 '이 폴더 사용'을 누르세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onPick, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("통화 녹음 폴더 선택") }
            Text(
                "원본 녹음 파일은 수정하거나 지우지 않으며, 모든 처리는 폰 안에서 이루어집니다. " +
                    "통화 내용을 공유할 때는 상대방의 사생활을 존중해 주세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FolderCard(label: String, autoOn: Boolean, onAuto: (Boolean) -> Unit, onChange: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("녹음 폴더", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = onChange) { Text("변경") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp, end = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("새 통화 자동 정리", style = MaterialTheme.typography.titleSmall)
                    Text("앱을 열면 새 통화를 차례로 텍스트·요약으로 정리",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = autoOn, onCheckedChange = onAuto)
            }
        }
    }
}

@Composable
private fun CallRow(
    item: CallItem,
    working: Boolean,
    busy: Boolean,
    phase: String,
    progress: Int,
    onProcess: () -> Unit,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(item.who, style = MaterialTheme.typography.titleSmall)
            val mb = item.sizeBytes / 1024f / 1024f
            Text(
                "${java.text.SimpleDateFormat("yyyy.MM.dd (E) HH:mm", Locale.KOREA).format(item.time)} · " +
                    String.format(Locale.KOREA, "%.1f", mb) + "MB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            when {
                working -> {
                    Text("$phase · $progress%", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, end = 8.dp)
                    )
                }
                item.hasText -> FilledTonalButton(
                    onClick = onOpen, modifier = Modifier.fillMaxWidth().padding(end = 8.dp)
                ) { Text("📝 통화 요약 보기") }
                else -> OutlinedButton(
                    onClick = onProcess, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp)
                ) { Text(if (busy) "다른 통화 정리 중…" else "텍스트 변환 + 요약") }
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (working) TextButton(onClick = onCancel) { Text("취소") }
                else if (item.hasText) TextButton(onClick = onDelete) {
                    Text("정리 결과 삭제", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
