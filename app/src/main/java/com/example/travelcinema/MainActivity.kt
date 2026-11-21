package com.example.travelcinema

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

data class MediaEntry(
    val id: String,
    val uriString: String,
    val displayName: String,
    val isVideo: Boolean,
    val lat: Double?,
    val lng: Double?,
    val timestampMs: Long?,
    val caption: String = ""
)

data class TravelStop(
    val id: String,
    val title: String,
    val lat: Double,
    val lng: Double,
    val items: List<MediaEntry>
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF4FC3F7),
                    background = Color(0xFF0F141A),
                    surface = Color(0xFF18202A),
                    onPrimary = Color(0xFF081018),
                    onBackground = Color(0xFFE6EDF3),
                    onSurface = Color(0xFFE6EDF3)
                )
            ) {
                TravelCinemaApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TravelCinemaApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tripTitle by remember { mutableStateOf("주말 여행 기록") }
    var selectedTab by remember { mutableStateOf(0) }
    val stops = remember { mutableStateListOf<TravelStop>() }
    val unassignedMedia = remember { mutableStateListOf<MediaEntry>() }

    var pendingMapTarget by remember { mutableStateOf<MediaEntry?>(null) }
    var editingStop by remember { mutableStateOf<TravelStop?>(null) }
    var editedTitle by remember { mutableStateOf("") }

    var isRendering by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var status by remember { mutableStateOf("사진/동영상을 선택해 여행 경로와 영상을 만들어 보세요.") }

    val permissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    fun clusterMedia(entries: List<MediaEntry>) {
        stops.clear()
        unassignedMedia.clear()

        val geoItems = entries
            .filter { it.lat != null && it.lng != null }
            .sortedBy { it.timestampMs ?: Long.MAX_VALUE }

        unassignedMedia.addAll(entries.filter { it.lat == null || it.lng == null })

        val clusters = mutableListOf<MutableList<MediaEntry>>()
        for (item in geoItems) {
            val lat = item.lat ?: continue
            val lng = item.lng ?: continue
            val last = clusters.lastOrNull()
            val anchor = last?.firstOrNull()
            val near = anchor?.lat != null && anchor.lng != null &&
                distanceMeters(anchor.lat, anchor.lng, lat, lng) <= 200.0
            val nearTime = anchor?.timestampMs == null || item.timestampMs == null ||
                abs(item.timestampMs - anchor.timestampMs) <= 3 * 60 * 60 * 1000L

            if (last != null && near && nearTime) {
                last.add(item)
            } else {
                clusters.add(mutableListOf(item))
            }
        }

        clusters.forEachIndexed { idx, group ->
            val avgLat = group.mapNotNull { it.lat }.average()
            val avgLng = group.mapNotNull { it.lng }.average()
            val hhmm = group.firstOrNull()?.timestampMs?.let {
                SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(it))
            }.orEmpty()
            val label = if (hhmm.isNotEmpty()) "경유지 ${idx + 1} ($hhmm)" else "경유지 ${idx + 1}"
            stops.add(
                TravelStop(
                    id = "stop_${idx}_${System.nanoTime()}",
                    title = label,
                    lat = avgLat,
                    lng = avgLng,
                    items = group.toList()
                )
            )
        }
    }

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            status = "메타데이터 분석 중..."
            val parsed = withContext(Dispatchers.IO) {
                uris.mapIndexed { idx, uri ->
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: SecurityException) { }
                    parseMediaUri(context, uri, idx)
                }
            }
            val combined = stops.flatMap { it.items } + unassignedMedia + parsed
            clusterMedia(combined)
            status = "분석 완료: 경유지 ${stops.size}곳, 미할당 ${unassignedMedia.size}개"
        }
    }

    fun assignCoordinates(media: MediaEntry, lat: Double, lng: Double) {
        unassignedMedia.removeAll { it.id == media.id }
        val moved = media.copy(lat = lat, lng = lng)
        val idx = stops.indexOfFirst { distanceMeters(it.lat, it.lng, lat, lng) <= 200.0 }
        if (idx >= 0) {
            val target = stops[idx]
            val merged = (target.items + moved).sortedBy { it.timestampMs ?: Long.MAX_VALUE }
            stops[idx] = target.copy(
                lat = merged.mapNotNull { it.lat }.average(),
                lng = merged.mapNotNull { it.lng }.average(),
                items = merged
            )
        } else {
            stops.add(
                TravelStop(
                    id = "stop_pin_${System.nanoTime()}",
                    title = "경유지 ${stops.size + 1}",
                    lat = lat,
                    lng = lng,
                    items = listOf(moved)
                )
            )
        }
        pendingMapTarget = null
        selectedTab = 0
        status = "'${media.displayName}' 위치 지정 완료"
    }

    fun saveBundle() {
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                val root = JSONObject().apply {
                    put("tripTitle", tripTitle)
                    put("stops", JSONArray().apply {
                        stops.forEach { stop ->
                            put(JSONObject().apply {
                                put("id", stop.id)
                                put("title", stop.title)
                                put("lat", stop.lat)
                                put("lng", stop.lng)
                                put("items", JSONArray().apply {
                                    stop.items.forEach { put(it.toJsonObject()) }
                                })
                            })
                        }
                    })
                    put("unassigned", JSONArray().apply {
                        unassignedMedia.forEach { put(it.toJsonObject()) }
                    })
                }
                val dir = File(context.getExternalFilesDir(null), "TravelCinemaBundles").apply { mkdirs() }
                val safe = tripTitle.replace(Regex("[^가-힣a-zA-Z0-9_-]"), "_").ifBlank { "trip" }
                val out = File(dir, "${safe}_bundle.json")
                out.writeText(root.toString(2), Charsets.UTF_8)
                out.absolutePath
            }
            status = "번들 저장됨: $path"
        }
    }

    fun loadBundle() {
        scope.launch {
            val count = withContext(Dispatchers.IO) {
                val dir = File(context.getExternalFilesDir(null), "TravelCinemaBundles")
                val file = dir.listFiles()?.filter { it.isFile && it.extension == "json" }
                    ?.maxByOrNull { it.lastModified() } ?: return@withContext -1
                val root = JSONObject(file.readText(Charsets.UTF_8))
                val loadedTitle = root.optString("tripTitle", "불러온 여행 기록")

                val loadedStops = mutableListOf<TravelStop>()
                val sArr = root.optJSONArray("stops") ?: JSONArray()
                for (i in 0 until sArr.length()) {
                    val sObj = sArr.getJSONObject(i)
                    val mArr = sObj.optJSONArray("items") ?: JSONArray()
                    val mList = mutableListOf<MediaEntry>()
                    for (j in 0 until mArr.length()) {
                        mList.add(mArr.getJSONObject(j).toMediaEntry())
                    }
                    loadedStops.add(
                        TravelStop(
                            id = sObj.optString("id", "stop_$i"),
                            title = sObj.optString("title", "경유지 ${i + 1}"),
                            lat = sObj.optDouble("lat", 37.2636),
                            lng = sObj.optDouble("lng", 127.0286),
                            items = mList
                        )
                    )
                }

                val loadedUnassigned = mutableListOf<MediaEntry>()
                val uArr = root.optJSONArray("unassigned") ?: JSONArray()
                for (i in 0 until uArr.length()) {
                    loadedUnassigned.add(uArr.getJSONObject(i).toMediaEntry())
                }

                withContext(Dispatchers.Main) {
                    tripTitle = loadedTitle
                    stops.clear()
                    stops.addAll(loadedStops)
                    unassignedMedia.clear()
                    unassignedMedia.addAll(loadedUnassigned)
                }
                loadedStops.sumOf { it.items.size } + loadedUnassigned.size
            }
            status = if (count >= 0) "번들 불러오기 완료 (미디어 ${count}개)" else "저장된 번들 파일이 없습니다."
        }
    }

    fun exportMp4() {
        val renderList = stops.flatMap { stop ->
            stop.items.map { m ->
                VideoRenderer.RenderMediaItem(
                    uriString = m.uriString,
                    isVideo = m.isVideo,
                    stopTitle = stop.title,
                    caption = m.caption.ifBlank { m.displayName }
                )
            }
        }
        if (renderList.isEmpty()) {
            status = "영상으로 만들 미디어가 없습니다."
            return
        }
        isRendering = true
        progress = 0f
        scope.launch {
            val res = VideoRenderer.renderTripVideo(context, tripTitle, renderList) { p, msg ->
                progress = p
                status = msg
            }
            isRendering = false
            status = res.fold(
                onSuccess = { "MP4 생성 완료: ${it.absolutePath}" },
                onFailure = { "MP4 생성 실패: ${it.message}" }
            )
        }
    }

    Scaffold(
        containerColor = Color(0xFF0F141A),
        topBar = {
            TopAppBar(
                title = { Text("여행 기록 스튜디오", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF161E28)),
                actions = {
                    Button(
                        onClick = { pickerLauncher.launch(arrayOf("image/*", "video/*")) },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("미디어 추가", fontSize = 13.sp)
                    }
                }
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(12.dp)
        ) {
            OutlinedTextField(
                value = tripTitle,
                onValueChange = { tripTitle = it },
                label = { Text("여행 기록 제목") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = selectedTab == 0, onClick = { selectedTab = 0 }, label = { Text("지도 (${stops.size})") })
                FilterChip(selected = selectedTab == 1, onClick = { selectedTab = 1 }, label = { Text("경유지 편집") })
                FilterChip(selected = selectedTab == 2, onClick = { selectedTab = 2 }, label = { Text("미할당 (${unassignedMedia.size})") })
            }
            Spacer(Modifier.height(8.dp))
            Surface(color = Color(0xFF18202A), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text(status, fontSize = 12.sp, color = Color(0xFFB8C7D9), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (isRendering) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            when (selectedTab) {
                0 -> {
                    val startLatLng = stops.firstOrNull()?.let { LatLng(it.lat, it.lng) } ?: LatLng(37.2636, 127.0286)
                    val camState = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(startLatLng, 12f) }
                    LaunchedEffect(stops.firstOrNull()?.lat, stops.firstOrNull()?.lng) {
                        stops.firstOrNull()?.let { camState.position = CameraPosition.fromLatLngZoom(LatLng(it.lat, it.lng), 12f) }
                    }
                    Column(Modifier.weight(1f).fillMaxWidth()) {
                        pendingMapTarget?.let { target ->
                            Row(
                                modifier = Modifier.fillMaxWidth().background(Color(0xFF283A4E), RoundedCornerShape(6.dp)).padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("지도 탭 위치 지정: ${target.displayName}", fontSize = 12.sp, color = Color(0xFF81D4FA), modifier = Modifier.weight(1f))
                                TextButton(onClick = { pendingMapTarget = null }) { Text("취소") }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        GoogleMap(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            cameraPositionState = camState,
                            onMapClick = { latLng ->
                                pendingMapTarget?.let { assignCoordinates(it, latLng.latitude, latLng.longitude) }
                            }
                        ) {
                            val pts = stops.map { LatLng(it.lat, it.lng) }
                            if (pts.size >= 2) Polyline(points = pts, color = Color(0xFF29B6F6), width = 8f)
                            stops.forEachIndexed { i, s ->
                                Marker(state = MarkerState(LatLng(s.lat, s.lng)), title = "${i + 1}. ${s.title}", snippet = "${s.items.size}개")
                            }
                        }
                    }
                }

                1 -> {
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(stops, key = { _, s -> s.id }) { sIdx, stop ->
                            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF18202A))) {
                                Column(Modifier.padding(10.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text(stop.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                        OutlinedButton(onClick = { editingStop = stop; editedTitle = stop.title }) { Text("이름 수정", fontSize = 12.sp) }
                                    }
                                    stop.items.forEachIndexed { mIdx, m ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).background(Color(0xFF10161E), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(m.displayName, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                            TextButton(onClick = {
                                                if (mIdx > 0) {
                                                    val list = stop.items.toMutableList()
                                                    val item = list.removeAt(mIdx)
                                                    list.add(mIdx - 1, item)
                                                    stops[sIdx] = stop.copy(items = list)
                                                }
                                            }, enabled = mIdx > 0) { Text("▲") }
                                            TextButton(onClick = {
                                                if (mIdx < stop.items.lastIndex) {
                                                    val list = stop.items.toMutableList()
                                                    val item = list.removeAt(mIdx)
                                                    list.add(mIdx + 1, item)
                                                    stops[sIdx] = stop.copy(items = list)
                                                }
                                            }, enabled = mIdx < stop.items.lastIndex) { Text("▼") }
                                            TextButton(onClick = {
                                                val list = stop.items.toMutableList()
                                                list.removeAt(mIdx)
                                                if (list.isEmpty()) stops.removeAt(sIdx) else stops[sIdx] = stop.copy(items = list)
                                            }) { Text("삭제", color = Color(0xFFEF5350)) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                else -> {
                    Column(Modifier.weight(1f).fillMaxWidth()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { saveBundle() }, modifier = Modifier.weight(1f)) { Text("번들 저장") }
                            OutlinedButton(onClick = { loadBundle() }, modifier = Modifier.weight(1f)) { Text("번들 불러오기") }
                        }
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            itemsIndexed(unassignedMedia, key = { _, m -> m.id }) { _, item ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF18202A)),
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        pendingMapTarget = item
                                        selectedTab = 0
                                        status = "지도에서 '${item.displayName}'의 위치를 탭하세요."
                                    }
                                ) {
                                    Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text(item.displayName, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        Text("지도 지정", color = Color(0xFF4FC3F7), fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { saveBundle() }, enabled = !isRendering, modifier = Modifier.weight(1f)) {
                    Text("번들 저장")
                }
                Button(onClick = { exportMp4() }, enabled = !isRendering, modifier = Modifier.weight(1.4f)) {
                    Text(if (isRendering) "MP4 생성 중..." else "MP4 영상 생성", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    editingStop?.let { targetStop ->
        AlertDialog(
            onDismissRequest = { editingStop = null },
            title = { Text("경유지 이름 수정") },
            text = {
                OutlinedTextField(
                    value = editedTitle,
                    onValueChange = { editedTitle = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val idx = stops.indexOfFirst { it.id == targetStop.id }
                    if (idx >= 0 && editedTitle.isNotBlank()) {
                        stops[idx] = stops[idx].copy(title = editedTitle.trim())
                    }
                    editingStop = null
                }) { Text("저장") }
            },
            dismissButton = {
                TextButton(onClick = { editingStop = null }) { Text("취소") }
            }
        )
    }
}

private fun parseMediaUri(context: Context, uri: Uri, idx: Int): MediaEntry {
    val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "media_$idx"
    val mime = context.contentResolver.getType(uri).orEmpty()
    val isVideo = mime.startsWith("video/") || name.endsWith(".mp4", true) || name.endsWith(".mov", true)
    var latLng: Pair<Double, Double>? = null
    var ts: Long? = null

    try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            exif.latLong?.let { latLng = Pair(it[0], it[1]) }
            val dt = exif.dateTimeOriginal ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            if (dt != null) {
                for (fmt in listOf("yyyy:MM:dd HH:mm:ss", "yyyy-MM-dd HH:mm:ss")) {
                    val parsed = runCatching { SimpleDateFormat(fmt, Locale.US).parse(dt)?.time }.getOrNull()
                    if (parsed != null) {
                        ts = parsed
                        break
                    }
                }
            }
        }
    } catch (_: Exception) { }

    if (isVideo && latLng == null) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val loc = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
            val m = loc?.let { Regex("([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)").find(it) }
            val vLat = m?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            val vLng = m?.groupValues?.getOrNull(2)?.toDoubleOrNull()
            if (vLat != null && vLng != null) latLng = Pair(vLat, vLng)
        } catch (_: Exception) { } finally {
            runCatching { r.release() }
        }
    }

    return MediaEntry(
        id = "${uri}_${idx}_${System.nanoTime()}",
        uriString = uri.toString(),
        displayName = name,
        isVideo = isVideo,
        lat = latLng?.first,
        lng = latLng?.second,
        timestampMs = ts,
        caption = name
    )
}

private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}

private fun MediaEntry.toJsonObject(): JSONObject = JSONObject().apply {
    put("id", id)
    put("uriString", uriString)
    put("displayName", displayName)
    put("isVideo", isVideo)
    put("lat", lat ?: JSONObject.NULL)
    put("lng", lng ?: JSONObject.NULL)
    put("timestampMs", timestampMs ?: JSONObject.NULL)
    put("caption", caption)
}

private fun JSONObject.toMediaEntry(): MediaEntry {
    val latVal: Double? = if (isNull("lat")) null else optDouble("lat", 0.0)
    val lngVal: Double? = if (isNull("lng")) null else optDouble("lng", 0.0)
    val tsVal: Long? = if (isNull("timestampMs")) null else optLong("timestampMs", 0L)
    return MediaEntry(
        id = optString("id", "item_${System.nanoTime()}"),
        uriString = optString("uriString", ""),
        displayName = optString("displayName", "media"),
        isVideo = optBoolean("isVideo", false),
        lat = latVal,
        lng = lngVal,
        timestampMs = tsVal,
        caption = optString("caption", "")
    )
}