package com.mplan.callsummary

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** 기본 전화 앱이 만든 통화 녹음 파일 하나 */
data class CallItem(
    val docUri: Uri,          // 원본 (읽기만 함)
    val fileName: String,
    val who: String,          // 상대방 이름 또는 번호
    val time: Long,           // 통화 시각
    val sizeBytes: Long,
    val localPath: String,    // 앱 전용 저장소의 복사본 경로
    val hasText: Boolean
) {
    val title: String get() = "$who · ${SimpleDateFormat("yyyy.MM.dd (E) HH:mm", Locale.KOREA).format(time)}"
    val imported: Boolean get() = File(localPath).exists()
}

/**
 * 통화 녹음 폴더 관리.
 * - 사용자가 시스템 폴더 선택기로 고른 폴더를 **읽기 전용**으로만 사용 (저장소 권한 없음)
 * - 원본 파일은 절대 수정·삭제하지 않음. 변환할 때 앱 전용 저장소로 복사해서 처리
 */
object CallStore {
    private const val PREFS = "calls"
    private const val KEY_TREE = "tree"
    private const val KEY_AUTO_SINCE = "auto_since"

    private val audioExt = setOf("m4a", "mp3", "amr", "3gp", "aac", "wav", "ogg", "opus", "mp4")

    /** 삼성 등 기본 통화 녹음 폴더 (폴더 선택기의 시작 위치로만 사용) */
    val initialFolderUri: Uri = DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents", "primary:Recordings/Call"
    )

    fun dir(ctx: Context) = File(ctx.filesDir, "calls").apply { mkdirs() }

    fun treeUri(ctx: Context): Uri? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE, null)?.let(Uri::parse)

    fun setTree(ctx: Context, uri: Uri) {
        // 이전에 고른 폴더 권한은 반납 → 필요한 폴더 하나만 읽기 권한 유지
        treeUri(ctx)?.let { old ->
            if (old != uri) try {
                ctx.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
        }
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TREE, uri.toString()).apply()
    }

    fun folderLabel(uri: Uri): String =
        try { DocumentsContract.getTreeDocumentId(uri).substringAfter(':').ifBlank { "내장 저장공간" } } catch (_: Exception) { "선택한 폴더" }

    /** 새 통화 자동 정리: 켠 시각 이후의 통화만 대상 (예전 통화를 한꺼번에 처리하지 않도록) */
    fun autoSince(ctx: Context): Long = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_AUTO_SINCE, 0L)

    fun setAuto(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_AUTO_SINCE, if (on) System.currentTimeMillis() - 10 * 60_000L else 0L).apply()
    }

    /** 폴더 안의 통화 녹음 목록 (최신순) */
    suspend fun list(ctx: Context): List<CallItem> = withContext(Dispatchers.IO) {
        val tree = treeUri(ctx) ?: return@withContext emptyList()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val out = ArrayList<CallItem>()
        try {
            ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val modified = if (c.isNull(2)) 0L else c.getLong(2)
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    val mime = c.getString(4) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext !in audioExt) continue
                    val (who, time) = parse(name, modified)
                    val key = "c" + Integer.toHexString((name + size).hashCode())
                    val local = File(dir(ctx), "$key.$ext").absolutePath
                    out += CallItem(
                        DocumentsContract.buildDocumentUriUsingTree(tree, id), name, who, time, size, local,
                        RecordingStore.summaryFile(local).exists() && RecordingStore.transcriptFile(local).exists()
                    )
                }
            }
        } catch (_: SecurityException) {
            return@withContext emptyList()   // 폴더 권한이 사라짐 → 화면에서 다시 고르게 함
        }
        out.sortedByDescending { it.time }.take(500)
    }

    /**
     * 파일 이름에서 상대방·시각 추출.
     * 예) "통화 녹음 홍길동_241016_120300.m4a", "Call recording 010-1234-5678_241016_120300.m4a"
     */
    fun parse(fileName: String, fallbackTime: Long): Pair<String, Long> {
        val base = fileName.substringBeforeLast('.')
            .removePrefix("통화 녹음").removePrefix("통화녹음").removePrefix("Call recording").trim()
        val m = Regex("""^(.*?)[_ ](\d{6})[_ ](\d{6})$""").find(base)
        if (m != null) {
            val who = m.groupValues[1].trim().ifBlank { "알 수 없음" }
            val t = try {
                SimpleDateFormat("yyMMddHHmmss", Locale.KOREA).parse(m.groupValues[2] + m.groupValues[3])?.time
            } catch (_: Exception) { null }
            return who to (t ?: fallbackTime)
        }
        return base.ifBlank { "통화" } to fallbackTime
    }

    /** 원본을 앱 전용 저장소로 복사 (원본은 읽기만) */
    suspend fun importCopy(ctx: Context, item: CallItem): File = withContext(Dispatchers.IO) {
        val f = File(item.localPath)
        if (f.exists() && f.length() > 0) return@withContext f
        val tmp = File(f.path + ".part")
        ctx.contentResolver.openInputStream(item.docUri).use { input ->
            requireNotNull(input) { "통화 녹음 파일을 열 수 없습니다." }
            tmp.outputStream().use { input.copyTo(it) }
        }
        tmp.renameTo(f)
        f
    }

    /** 이 앱이 만든 복사본·텍스트·요약만 삭제 (원본 통화 녹음은 그대로) */
    fun deleteResult(ctx: Context, item: CallItem) {
        val f = File(item.localPath)
        if (f.parentFile?.canonicalPath != dir(ctx).canonicalPath) return
        RecordingStore.transcriptFile(item.localPath).delete()
        RecordingStore.summaryFile(item.localPath).delete()
        f.delete()
    }

    fun durationMs(item: CallItem): Long =
        if (item.imported) AudioDecoder.durationMs(item.localPath) else 0L
}
