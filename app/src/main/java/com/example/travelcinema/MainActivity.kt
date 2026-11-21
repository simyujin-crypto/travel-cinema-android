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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

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
        val missing = permissions.filter { perm ->
            ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    fun clusterMedia(entries: List<MediaEntry>) {
        stops.clear()
        unassignedMedia.clear()

        val geoItems = entries
            .filter { item -> item.lat != null && item.lng != null }
            .sortedBy { item -> item.timestampMs ?: Long.MAX_VALUE }

        unassignedMedia.addAll(entries.filter { item -> item.lat == null || item.lng == null })

        val clusters = mutableListOf<MutableList<MediaEntry>>()
        for (item in geoItems) {
            val lat = item.lat ?: continue
            val lng = item.lng ?: continue
            val last = clusters.lastOrNull()
            val anchor = last?.firstOrNull()
            val anchorLat = anchor?.lat
            val anchorLng = anchor?.lng
            val near = if (anchorLat != null && anchorLng != null) {
                distanceMeters(anchorLat, anchorLng, lat, lng) <= 200.0
            } else {
                false
            }
            val anchorTs = anchor?.timestampMs
            val itemTs = item.timestampMs
            val nearTime = if (anchorTs != null && itemTs != null) {
                abs(itemTs - anchorTs) <= 3 * 60 * 60 * 1000L
            } else {
                true
            }

            if (last != null && near && nearTime) {
                last.add(item)
            } else {
                clusters.add(mutableListOf(item))
            }
        }

        clusters.forEachIndexed { idx, group ->
            val avgLat = group.mapNotNull { m -> m.lat }.average()
            val avgLng = group.mapNotNull { m -> m.lng }.average()
            val firstTs = group.firstOrNull()?.timestampMs
            val hhmm = if (firstTs != null) {
                SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(firstTs))
            } else {
                ""
            }
            val label = if (hhmm.isNotEmpty()) {
                "경유지 ${idx + 1} ($hhmm)"
            } else {
                "경유지 ${idx + 1}"
            }
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
                    } catch (_: SecurityException) {
                    }
                    parseMediaUri(context, uri, idx)
                }
            }
            val combined = stops.flatMap { s -> s.items } + unassignedMedia + parsed
            clusterMedia(combined)
            status = "분석 완료: 경유지 ${stops.size}곳, 미할당 ${unassignedMedia.size}개"
        }
    }

    fun assignCoordinates(media: MediaEntry, lat: Double, lng: Double) {
        unassignedMedia.removeAll { item -> item.id == media.id }
        val moved = media.copy(lat = lat, lng = lng)
        val idx = stops.indexOfFirst { s -> distanceMeters(s.lat, s.lng, lat, lng) <= 200.0 }
        if (idx >= 0) {
            val target = stops[idx]
            val merged = (target.items + moved).sortedBy { m -> m.timestampMs ?: Long.MAX_VALUE }
            stops[idx] = target.copy(
                lat = merged.mapNotNull { m -> m.lat }.average(),
                lng = merged.mapNotNull { m -> m.lng }.average(),
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
                val stopsArray = JSONArray()
                stops.forEach { stop ->
                    val itemsArray = JSONArray()
                    stop.items.forEach { m -> itemsArray.put(m.toJsonObject()) }
                    val sObj = JSONObject().apply {
                        put("id", stop.id)
                        put("title", stop.title)
                        put("lat", stop.lat)
                        put("lng", stop.lng)
                        put("items", itemsArray)
                    }
                    stopsArray.put(sObj)
                }

                val unassignedArray = JSONArray()
                unassignedMedia.forEach { m -> unassignedArray.put(m.toJsonObject()) }

                val root = JSONObject().apply {
                    put("tripTitle", tripTitle)
                    put("stops", stopsArray)
                    put("unassigned", unassignedArray)
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
                val files = dir.listFiles()?.filter { f -> f.isFile && f.extension == "json" }
                val file = files?.maxByOrNull { f -> f.lastModified() }
                if (file == null) {
                    -1
                } else {
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
                    loadedStops.sumOf { s -> s.items.size } + loadedUnassigned.size
                }
            }
            status = if (count >= 0) {
                "번들 불러오기 완료 (미디어 ${count}개)"
            } else {
                "저장된 번들 파일이 없습니다."
            }
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
                onSuccess = { outFile -> "MP4 생성 완료: ${outFile.absolutePath}" },
                onFailure = { err -> "MP4 생성 실패: ${err.message}" }
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
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2B6CB0),
                            contentColor = Color.White
                        ),
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
                onValueChange = { newText -> tripTitle = newText },
                label = { Text("여행 기록 제목") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    label = { Text("지도 (${stops.size})") }
                )
                FilterChip(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    label = { Text("경유지 편집") }
                )
                FilterChip(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    label = { Text("미할당 (${unassignedMedia.size})") }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                color = Color(0xFF18202A),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = status,
                        fontSize = 12.sp,
                        color = Color(0xFFB8C7D9),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isRendering) {
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            when (selectedTab) {
                0 -> {
                    val firstStop = stops.firstOrNull()
                    val startLatLng = if (firstStop != null) {
                        LatLng(firstStop.lat, firstStop.lng)
                    } else {
                        LatLng(37.2636, 127.0286)
                    }
                    val camState = rememberCameraPositionState {
                        position = CameraPosition.fromLatLngZoom(startLatLng, 12f)
                    }
                    LaunchedEffect(firstStop?.lat, firstStop?.lng) {
                        if (firstStop != null) {
                            camState.position = CameraPosition.fromLatLngZoom(
                                LatLng(firstStop.lat, firstStop.lng),
                                12f
                            )
                        }
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        val target = pendingMapTarget
                        if (target != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF283A4E), RoundedCornerShape(6.dp))
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "지도 탭 위치 지정: ${target.displayName}",
                                    fontSize = 12.sp,
                                    color = Color(0xFF81D4FA),
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { pendingMapTarget = null }) {
                                    Text("취소")
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        GoogleMap(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            cameraPositionState = camState,
                            onMapClick = { latLng ->
                                val activeTarget = pendingMapTarget
                                if (activeTarget != null) {
                                    assignCoordinates(activeTarget, latLng.latitude, latLng.longitude)
                                }
                            }
                        ) {
                            val pts = stops.map { s -> LatLng(s.lat, s.lng) }
                            if (pts.size >= 2) {
                                Polyline(points = pts, color = Color(0xFF29B6F6), width = 8f)
                            }
                            stops.forEachIndexed { i, s ->
                                Marker(
                                    state = MarkerState(position = LatLng(s.lat, s.lng)),
                                    title = "${i + 1}. ${s.title}",
                                    snippet = "${s.items.size}개"
                                )
                            }
                        }
                    }
                }

                1 -> {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(stops, key = { _, s -> s.id }) { sIdx, stop ->
                            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF18202A))) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stop.title,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        OutlinedButton(onClick = {
                                            editingStop = stop
                                            editedTitle = stop.title
                                        }) {
                                            Text("이름 수정", fontSize = 12.sp)
                                        }
                                    }
                                    stop.items.forEachIndexed { mIdx, m ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 4.dp)
                                                .background(Color(0xFF10161E), RoundedCornerShape(6.dp))
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = m.displayName,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            TextButton(
                                                onClick = {
                                                    if (mIdx > 0) {
                                                        val list = stop.items.toMutableList()
                                                        val item = list.removeAt(mIdx)
                                                        list.add(mIdx - 1, item)
                                                        stops[sIdx] = stop.copy(items = list)
                                                    }
                                                },
                                                enabled = mIdx > 0
                                            ) {
                                                Text("▲")
                                            }
                                            TextButton(
                                                onClick = {
                                                    if (mIdx < stop.items.lastIndex) {
                                                        val list = stop.items.toMutableList()
                                                        val item = list.removeAt(mIdx)
                                                        list.add(mIdx + 1, item)
                                                        stops[sIdx] = stop.copy(items = list)
                                                    }
                                                },
                                                enabled = mIdx < stop.items.lastIndex
                                            ) {
                                                Text("▼")
                                            }
                                            TextButton(onClick = {
                                                val list = stop.items.toMutableList()
                                                list.removeAt(mIdx)
                                                if (list.isEmpty()) {
                                                    stops.removeAt(sIdx)
                                                } else {
                                                    stops[sIdx] = stop.copy(items = list)
                                                }
                                            }) {
                                                Text("삭제", color = Color(0xFFEF5350))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                else -> {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { saveBundle() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("번들 저장")
                            }
                            OutlinedButton(
                                onClick = { loadBundle() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("번들 불러오기")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            itemsIndexed(unassignedMedia, key = { _, m -> m.id }) { _, item ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF18202A)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            pendingMapTarget = item
                                            selectedTab = 0
                                            status = "지도에서 '${item.displayName}'의 위치를 탭하세요."
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = item.displayName,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("지도 지정", color = Color(0xFF4FC3F7), fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { saveBundle() },
                    enabled = !isRendering,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("번들 저장")
                }
                Button(
                    onClick = { exportMp4() },
                    enabled = !isRendering,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00897B),
                        contentColor = Color.White
                    ),
                    modifier = Modifier.weight(1.4f)
                ) {
                    Text(
                        text = if (isRendering) "MP4 생성 중..." else "MP4 영상 생성",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    val targetStop = editingStop
    if (targetStop != null) {
        AlertDialog(
            onDismissRequest = { editingStop = null },
            title = { Text("경유지 이름 수정") },
            text = {
                OutlinedTextField(
                    value = editedTitle,
                    onValueChange = { newTitle -> editedTitle = newTitle },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val idx = stops.indexOfFirst { s -> s.id == targetStop.id }
                    if (idx >= 0 && editedTitle.isNotBlank()) {
                        stops[idx] = stops[idx].copy(title = editedTitle.trim())
                    }
                    editingStop = null
                }) {
                    Text("저장")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingStop = null }) {
                    Text("취소")
                }
            }
        )
    }
}

private fun parseMediaUri(context: Context, uri: Uri, idx: Int): MediaEntry {
    val rawSegment = uri.lastPathSegment
    val afterSlash = if (rawSegment != null) rawSegment.substringAfterLast('/') else "media_$idx"
    val name = afterSlash.substringAfterLast(':')
    val mime = context.contentResolver.getType(uri) ?: ""
    val isVideo = mime.startsWith("video/") ||
        name.endsWith(".mp4", ignoreCase = true) ||
        name.endsWith(".mov", ignoreCase = true)

    var latVal: Double? = null
    var lngVal: Double? = null
    var tsVal: Long? = null

    try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            val coords = exif.latLong
            if (coords != null && coords.size >= 2) {
                latVal = coords[0]
                lngVal = coords[1]
            }
            val dtRaw = exif.dateTimeOriginal ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            if (dtRaw != null) {
                tsVal = parseDateToMillis(dtRaw)
            }
        }
    } catch (_: Exception) {
    }

    if (isVideo && (latVal == null || lngVal == null)) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val locStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
            if (locStr != null) {
                val match = Regex("([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)").find(locStr)
                if (match != null) {
                    val parsedLat = match.groupValues[1].toDoubleOrNull()
                    val parsedLng = match.groupValues[2].toDoubleOrNull()
                    if (parsedLat != null && parsedLng != null) {
                        latVal = parsedLat
                        lngVal = parsedLng
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    return MediaEntry(
        id = "${uri}_${idx}_${System.nanoTime()}",
        uriString = uri.toString(),
        displayName = name,
        isVideo = isVideo,
        lat = latVal,
        lng = lngVal,
        timestampMs = tsVal,
        caption = name
    )
}

private fun parseDateToMillis(raw: String): Long? {
    val patterns = arrayOf("yyyy:MM:dd HH:mm:ss", "yyyy-MM-dd HH:mm:ss")
    for (pattern in patterns) {
        try {
            val formatter = SimpleDateFormat(pattern, Locale.US)
            val parsedDate = formatter.parse(raw)
            if (parsedDate != null) {
                return parsedDate.time
            }
        } catch (_: Exception) {
        }
    }
    return null
}

private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val earthRadius = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val sinLat = sin(dLat / 2.0).pow(2)
    val sinLon = sin(dLon / 2.0).pow(2)
    val cosProduct = cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2))
    val a = sinLat + cosProduct * sinLon
    val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    return earthRadius * c
}

private fun MediaEntry.toJsonObject(): JSONObject {
    val obj = JSONObject()
    obj.put("id", id)
    obj.put("uriString", uriString)
    obj.put("displayName", displayName)
    obj.put("isVideo", isVideo)
    if (lat != null) obj.put("lat", lat) else obj.put("lat", JSONObject.NULL)
    if (lng != null) obj.put("lng", lng) else obj.put("lng", JSONObject.NULL)
    if (timestampMs != null) obj.put("timestampMs", timestampMs) else obj.put("timestampMs", JSONObject.NULL)
    obj.put("caption", caption)
    return obj
}

private fun JSONObject.toMediaEntry(): MediaEntry {
    val parsedLat: Double? = if (isNull("lat")) null else optDouble("lat", 0.0)
    val parsedLng: Double? = if (isNull("lng")) null else optDouble("lng", 0.0)
    val parsedTs: Long? = if (isNull("timestampMs")) null else optLong("timestampMs", 0L)
    return MediaEntry(
        id = optString("id", "item_${System.nanoTime()}"),
        uriString = optString("uriString", ""),
        displayName = optString("displayName", "media"),
        isVideo = optBoolean("isVideo", false),
        lat = parsedLat,
        lng = parsedLng,
        timestampMs = parsedTs,
        caption = optString("caption", "")
    )
}