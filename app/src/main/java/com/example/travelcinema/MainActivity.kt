package com.example.travelcinema

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import kotlin.math.atan2
import kotlin.math.cos
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
                colorScheme = androidx.compose.material3.darkColorScheme(
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
    var selectedTab by remember { mutableStateOf(0) } // 0: 경로/지도, 1: 경유지/편집, 2: 미할당/번들
    val stops = remember { mutableStateListOf<TravelStop>() }
    val unassignedMedia = remember { mutableStateListOf<MediaEntry>() }

    var pendingMapTargetMedia by remember { mutableStateOf<MediaEntry?>(null) }
    var editingStop by remember { mutableStateOf<TravelStop?>(null) }
    var editedStopTitle by remember { mutableStateOf("") }

    var isRendering by remember { mutableStateOf(false) }
    var renderProgress by remember { mutableStateOf(0f) }
    var statusMessage by remember { mutableStateOf("사진/동영상을 선택해 여행 경로와 영상을 만들어 보세요.") }

    val requiredPermissions = remember {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.READ_MEDIA_IMAGES)
            list.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            list.add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
        list.toTypedArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val grantedCount = grants.values.count { it }
        statusMessage = "권한 $grantedCount 개 처리 완료"
    }

    LaunchedEffect(Unit) {
        val missing = requiredPermissions.filter { perm ->
            ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    fun applyClustering(entries: List<MediaEntry>) {
        stops.clear()
        unassignedMedia.clear()

        val validGeo = entries
            .filter { entry -> entry.lat != null && entry.lng != null }
            .sortedBy { entry -> entry.timestampMs ?: Long.MAX_VALUE }

        val noGeo = entries.filter { entry -> entry.lat == null || entry.lng == null }
        unassignedMedia.addAll(noGeo)

        val clusterList = mutableListOf<MutableList<MediaEntry>>()
        for (entry in validGeo) {
            val lat = entry.lat ?: continue
            val lng = entry.lng ?: continue
            val lastCluster = clusterList.lastOrNull()
            val anchor = lastCluster?.firstOrNull()
            val anchorLat = anchor?.lat
            val anchorLng = anchor?.lng

            val withinRadius = if (anchorLat != null && anchorLng != null) {
                haversineMeters(anchorLat, anchorLng, lat, lng) <= 200.0
            } else {
                false
            }

            val withinTime = if (anchor?.timestampMs != null && entry.timestampMs != null) {
                kotlin.math.abs(entry.timestampMs - anchor.timestampMs) <= 3 * 60 * 60 * 1000L
            } else {
                true
            }

            if (lastCluster != null && withinRadius && withinTime) {
                lastCluster.add(entry)
            } else {
                clusterList.add(mutableListOf(entry))
            }
        }

        clusterList.forEachIndexed { idx, group ->
            val avgLat = group.mapNotNull { m -> m.lat }.average()
            val avgLng = group.mapNotNull { m -> m.lng }.average()
            val timeLabel = group.firstOrNull()?.timestampMs?.let { ts ->
                SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(ts))
            } ?: ""
            val title = if (timeLabel.isNotBlank()) {
                "경유지 ${idx + 1} ($timeLabel)"
            } else {
                "경유지 ${idx + 1}"
            }
            stops.add(
                TravelStop(
                    id = "stop_${idx + 1}_${System.currentTimeMillis()}",
                    title = title,
                    lat = avgLat,
                    lng = avgLng,
                    items = group.toList()
                )
            )
        }
    }

    val mediaPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            statusMessage = "메타데이터(EXIF/GPS/시간) 분석 중..."
            val parsed = withContext(Dispatchers.IO) {
                uris.mapIndexedNotNull { index, uri ->
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: SecurityException) {
                    }
                    extractMediaEntry(context, uri, index)
                }
            }
            val existingAll = stops.flatMap { stop -> stop.items } + unassignedMedia
            applyClustering(existingAll + parsed)
            statusMessage = "총 ${parsed.size}개 미디어 분석 완료 (경유지 ${stops.size}곳, 미할당 ${unassignedMedia.size}개)"
        }
    }

    fun assignMediaToCoordinates(media: MediaEntry, lat: Double, lng: Double) {
        unassignedMedia.removeAll { item -> item.id == media.id }
        val updatedMedia = media.copy(lat = lat, lng = lng)

        val targetIndex = stops.indexOfFirst { stop ->
            haversineMeters(stop.lat, stop.lng, lat, lng) <= 200.0
        }

        if (targetIndex >= 0) {
            val currentStop = stops[targetIndex]
            val mergedItems = (currentStop.items + updatedMedia)
                .sortedBy { item -> item.timestampMs ?: Long.MAX_VALUE }
            val newLat = mergedItems.mapNotNull { item -> item.lat }.average()
            val newLng = mergedItems.mapNotNull { item -> item.lng }.average()
            stops[targetIndex] = currentStop.copy(
                lat = newLat,
                lng = newLng,
                items = mergedItems
            )
        } else {
            stops.add(
                TravelStop(
                    id = "stop_custom_${System.currentTimeMillis()}",
                    title = "경유지 ${stops.size + 1}",
                    lat = lat,
                    lng = lng,
                    items = listOf(updatedMedia)
                )
            )
        }
        pendingMapTargetMedia = null
        selectedTab = 0
        statusMessage = "'${media.displayName}' 위치 지정 완료 (${String.format(Locale.US, "%.5f, %.5f", lat, lng)})"
    }

    fun saveOfflineBundle() {
        scope.launch {
            val filePath = withContext(Dispatchers.IO) {
                val rootObj = JSONObject().apply {
                    put("tripTitle", tripTitle)
                    put("savedAt", System.currentTimeMillis())

                    val stopsArr = JSONArray()
                    stops.forEach { stop ->
                        val sObj = JSONObject().apply {
                            put("id", stop.id)
                            put("title", stop.title)
                            put("lat", stop.lat)
                            put("lng", stop.lng)
                            val itemsArr = JSONArray()
                            stop.items.forEach { m -> itemsArr.put(m.toJson()) }
                            put("items", itemsArr)
                        }
                        stopsArr.put(sObj)
                    }
                    put("stops", stopsArr)

                    val unassignedArr = JSONArray()
                    unassignedMedia.forEach { m -> unassignedArr.put(m.toJson()) }
                    put("unassigned", unassignedArr)
                }

                val dir = File(context.getExternalFilesDir(null), "TravelCinemaBundles").apply { mkdirs() }
                val safeName = tripTitle.replace(Regex("[^가-힣a-zA-Z0-9_-]"), "_").ifBlank { "trip_bundle" }
                val outFile = File(dir, "${safeName}_bundle.json")
                outFile.writeText(rootObj.toString(2), Charsets.UTF_8)
                outFile.absolutePath
            }
            statusMessage = "오프라인 여행 번들 저장 완료: $filePath"
        }
    }

    fun loadOfflineBundle() {
        scope.launch {
            val loadedCount = withContext(Dispatchers.IO) {
                val dir = File(context.getExternalFilesDir(null), "TravelCinemaBundles")
                val latestFile = dir.listFiles()
                    ?.filter { f -> f.isFile && f.name.endsWith(".json") }
                    ?.maxByOrNull { f -> f.lastModified() }
                    ?: return@withContext -1

                val rootObj = JSONObject(latestFile.readText(Charsets.UTF_8))
                val loadedTitle = rootObj.optString("tripTitle", "불러온 여행 기록")

                val parsedStops = mutableListOf<TravelStop>()
                val stopsArr = rootObj.optJSONArray("stops") ?: JSONArray()
                for (i in 0 until stopsArr.length()) {
                    val sObj = stopsArr.getJSONObject(i)
                    val itemsList = mutableListOf<MediaEntry>()
                    val itemsArr = sObj.optJSONArray("items") ?: JSONArray()
                    for (j in 0 until itemsArr.length()) {
                        itemsList.add(mediaEntryFromJson(itemsArr.getJSONObject(j)))
                    }
                    parsedStops.add(
                        TravelStop(
                            id = sObj.optString("id", "stop_$i"),
                            title = sObj.optString("title", "경유지 ${i + 1}"),
                            lat = sObj.optDouble("lat", 37.2636),
                            lng = sObj.optDouble("lng", 127.0286),
                            items = itemsList
                        )
                    )
                }

                val parsedUnassigned = mutableListOf<MediaEntry>()
                val unArr = rootObj.optJSONArray("unassigned") ?: JSONArray()
                for (i in 0 until unArr.length()) {
                    parsedUnassigned.add(mediaEntryFromJson(unArr.getJSONObject(i)))
                }

                withContext(Dispatchers.Main) {
                    tripTitle = loadedTitle
                    stops.clear()
                    stops.addAll(parsedStops)
                    unassignedMedia.clear()
                    unassignedMedia.addAll(parsedUnassigned)
                }
                parsedStops.sumOf { s -> s.items.size } + parsedUnassigned.size
            }

            statusMessage = if (loadedCount >= 0) {
                "오프라인 번들 복원 완료 (미디어 ${loadedCount}개)"
            } else {
                "저장된 오프라인 여행 번들(.json)이 없습니다."
            }
        }
    }

    fun startVideoExport() {
        val renderItems = stops.flatMap { stop ->
            stop.items.map { media ->
                VideoRenderer.RenderMediaItem(
                    uriString = media.uriString,
                    isVideo = media.isVideo,
                    stopTitle = stop.title,
                    caption = media.caption.ifBlank { media.displayName }
                )
            }
        }
        if (renderItems.isEmpty()) {
            statusMessage = "영상으로 만들 경유지 미디어가 없습니다."
            return
        }
        isRendering = true
        renderProgress = 0f
        scope.launch {
            val result = VideoRenderer.renderTripVideo(
                context = context,
                tripTitle = tripTitle,
                items = renderItems,
                onProgress = { prog, msg ->
                    renderProgress = prog
                    statusMessage = msg
                }
            )
            isRendering = false
            result.onSuccess { file ->
                statusMessage = "MP4 생성 완료: ${file.absolutePath}"
            }.onFailure { err ->
                statusMessage = "MP4 생성 실패: ${err.message ?: "알 수 없는 오류"}"
            }
        }
    }

    Scaffold(
        containerColor = Color(0xFF0F141A),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "여행 기록 스튜디오",
                        color = Color(0xFFE6EDF3),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF161E28)
                ),
                actions = {
                    Button(
                        onClick = {
                            mediaPickerLauncher.launch(arrayOf("image/*", "video/*"))
                        },
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
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(12.dp)
        ) {
            OutlinedTextField(
                value = tripTitle,
                onValueChange = { newVal -> tripTitle = newVal },
                label = { Text("여행 기록 제목", color = Color(0xFF9FB0C3)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    label = { Text("지도·경로 (${stops.size})") }
                )
                FilterChip(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    label = { Text("경유지 편집") }
                )
                FilterChip(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    label = { Text("미할당·번들 (${unassignedMedia.size})") }
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
                        text = statusMessage,
                        color = Color(0xFFB8C7D9),
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isRendering) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .background(Color(0xFF2A3644), RoundedCornerShape(3.dp))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(renderProgress.coerceIn(0f, 1f))
                                    .fillMaxHeight()
                                    .background(Color(0xFF4FC3F7), RoundedCornerShape(3.dp))
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            when (selectedTab) {
                0 -> MapRouteSection(
                    stops = stops,
                    pendingTargetMedia = pendingMapTargetMedia,
                    onCancelTarget = { pendingMapTargetMedia = null },
                    onMapClick = { latLng ->
                        val target = pendingMapTargetMedia
                        if (target != null) {
                            assignMediaToCoordinates(target, latLng.latitude, latLng.longitude)
                        }
                    },
                    modifier = Modifier.weight(1f)
                )

                1 -> StopEditorSection(
                    stops = stops,
                    onEditStopTitle = { stop ->
                        editingStop = stop
                        editedStopTitle = stop.title
                    },
                    onMoveMediaUp = { stopIdx, mediaIdx ->
                        if (mediaIdx > 0) {
                            val current = stops[stopIdx]
                            val mutable = current.items.toMutableList()
                            val moved = mutable.removeAt(mediaIdx)
                            mutable.add(mediaIdx - 1, moved)
                            stops[stopIdx] = current.copy(items = mutable)
                        }
                    },
                    onMoveMediaDown = { stopIdx, mediaIdx ->
                        val current = stops[stopIdx]
                        if (mediaIdx < current.items.lastIndex) {
                            val mutable = current.items.toMutableList()
                            val moved = mutable.removeAt(mediaIdx)
                            mutable.add(mediaIdx + 1, moved)
                            stops[stopIdx] = current.copy(items = mutable)
                        }
                    },
                    onDeleteMedia = { stopIdx, mediaIdx ->
                        val current = stops[stopIdx]
                        val mutable = current.items.toMutableList()
                        mutable.removeAt(mediaIdx)
                        if (mutable.isEmpty()) {
                            stops.removeAt(stopIdx)
                        } else {
                            stops[stopIdx] = current.copy(items = mutable)
                        }
                    },
                    modifier = Modifier.weight(1f)
                )

                else -> UnassignedAndBundleSection(
                    unassignedMedia = unassignedMedia,
                    onPickOnMap = { media ->
                        pendingMapTargetMedia = media
                        selectedTab = 0
                        statusMessage = "지도에서 '${media.displayName}'의 위치를 탭하세요."
                    },
                    onSaveBundle = { saveOfflineBundle() },
                    onLoadBundle = { loadOfflineBundle() },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { saveOfflineBundle() },
                    enabled = !isRendering,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("번들 저장", fontSize = 13.sp)
                }
                Button(
                    onClick = { startVideoExport() },
                    enabled = !isRendering,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00897B),
                        contentColor = Color.White
                    ),
                    modifier = Modifier.weight(1.4f)
                ) {
                    Text(
                        text = if (isRendering) "MP4 생성 중..." else "MP4 영상 생성",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    val currentEditing = editingStop
    if (currentEditing != null) {
        AlertDialog(
            onDismissRequest = { editingStop = null },
            title = { Text("경유지 이름 수정") },
            text = {
                OutlinedTextField(
                    value = editedStopTitle,
                    onValueChange = { txt -> editedStopTitle = txt },
                    singleLine = true,
                    label = { Text("새 이름") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val idx = stops.indexOfFirst { s -> s.id == currentEditing.id }
                        if (idx >= 0 && editedStopTitle.isNotBlank()) {
                            stops[idx] = stops[idx].copy(title = editedStopTitle.trim())
                        }
                        editingStop = null
                    }
                ) {
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

@Composable
private fun MapRouteSection(
    stops: List<TravelStop>,
    pendingTargetMedia: MediaEntry?,
    onCancelTarget: () -> Unit,
    onMapClick: (LatLng) -> Unit,
    modifier: Modifier = Modifier
) {
    val initialPoint = stops.firstOrNull()?.let { s -> LatLng(s.lat, s.lng) }
        ?: LatLng(37.2636, 127.0286)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(initialPoint, 12f)
    }

    LaunchedEffect(stops.firstOrNull()?.lat, stops.firstOrNull()?.lng) {
        val first = stops.firstOrNull()
        if (first != null) {
            cameraPositionState.position = CameraPosition.fromLatLngZoom(
                LatLng(first.lat, first.lng),
                12f
            )
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (pendingTargetMedia != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF283A4E), RoundedCornerShape(6.dp))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "위치 지정 중: ${pendingTargetMedia.displayName}",
                    color = Color(0xFF81D4FA),
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onCancelTarget) {
                    Text("취소", fontSize = 12.sp)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                onMapClick = { latLng -> onMapClick(latLng) }
            ) {
                val points = stops.map { s -> LatLng(s.lat, s.lng) }
                if (points.size >= 2) {
                    Polyline(
                        points = points,
                        color = Color(0xFF29B6F6),
                        width = 8f
                    )
                }
                stops.forEachIndexed { index, stop ->
                    Marker(
                        state = remember(stop.lat, stop.lng) { MarkerState(position = LatLng(stop.lat, stop.lng)) },
                        title = "${index + 1}. ${stop.title}",
                        snippet = "미디어 ${stop.items.size}개"
                    )
                }
            }
        }
    }
}

@Composable
private fun StopEditorSection(
    stops: List<TravelStop>,
    onEditStopTitle: (TravelStop) -> Unit,
    onMoveMediaUp: (Int, Int) -> Unit,
    onMoveMediaDown: (Int, Int) -> Unit,
    onDeleteMedia: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (stops.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("표시할 경유지가 없습니다. 미디어를 추가해 주세요.", color = Color(0xFF8C9BAE))
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(stops, key = { _, stop -> stop.id }) { stopIdx, stop ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF18202A)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stop.title,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE6EDF3),
                                fontSize = 15.sp
                            )
                            Text(
                                text = "${String.format(Locale.US, "%.4f, %.4f", stop.lat, stop.lng)} · ${stop.items.size}개",
                                color = Color(0xFF9FB0C3),
                                fontSize = 11.sp
                            )
                        }
                        OutlinedButton(onClick = { onEditStopTitle(stop) }) {
                            Text("이름 수정", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    stop.items.forEachIndexed { mediaIdx, media ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .background(Color(0xFF10161E), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (media.isVideo) "[영상]" else "[사진]",
                                color = if (media.isVideo) Color(0xFFFFB74D) else Color(0xFF4FC3F7),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = media.displayName,
                                color = Color(0xFFDCE6F0),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { onMoveMediaUp(stopIdx, mediaIdx) },
                                enabled = mediaIdx > 0
                            ) {
                                Text("▲", fontSize = 12.sp)
                            }
                            TextButton(
                                onClick = { onMoveMediaDown(stopIdx, mediaIdx) },
                                enabled = mediaIdx < stop.items.lastIndex
                            ) {
                                Text("▼", fontSize = 12.sp)
                            }
                            TextButton(onClick = { onDeleteMedia(stopIdx, mediaIdx) }) {
                                Text("삭제", color = Color(0xFFEF5350), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UnassignedAndBundleSection(
    unassignedMedia: List<MediaEntry>,
    onPickOnMap: (MediaEntry) -> Unit,
    onSaveBundle: () -> Unit,
    onLoadBundle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onSaveBundle, modifier = Modifier.weight(1f)) {
                Text("번들 내보내기", fontSize = 12.sp)
            }
            OutlinedButton(onClick = onLoadBundle, modifier = Modifier.weight(1f)) {
                Text("번들 불러오기", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (unassignedMedia.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("GPS 정보가 없는 미할당 미디어가 없습니다.", color = Color(0xFF8C9BAE), fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(unassignedMedia, key = { _, item -> item.id }) { _, item ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF18202A)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPickOnMap(item) }
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.displayName,
                                    color = Color(0xFFE6EDF3),
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "탭하여 지도에서 위치 지정",
                                    color = Color(0xFF4FC3F7),
                                    fontSize = 11.sp
                                )
                            }
                            TextButton(onClick = { onPickOnMap(item) }) {
                                Text("지도 지정", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun extractMediaEntry(context: Context, uri: Uri, index: Int): MediaEntry {
    val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
        ?: "media_$index"
    val mime = context.contentResolver.getType(uri).orEmpty()
    val isVideo = mime.startsWith("video/") ||
        name.endsWith(".mp4", true) ||
        name.endsWith(".mov", true) ||
        name.endsWith(".mkv", true)

    var latLng: Pair<Double, Double>? = null
    var timestampMs: Long? = null

    try {
        openOriginalStream(context, uri)?.use { input ->
            val exif = ExifInterface(input)
            val latLong = exif.latLong
            if (latLong != null) {
                latLng = Pair(latLong[0], latLong[1])
            }
            val dtRaw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            if (dtRaw != null) {
                timestampMs = parseExifDate(dtRaw)
            }
        }
    } catch (e: Exception) {
    }

    if (timestampMs == null) {
        val nm = Regex("(20\\d{2})(\\d{2})(\\d{2})[_-]?(\\d{2})(\\d{2})(\\d{2})").find(name)
        if (nm != null) {
            val g = nm.groupValues
            timestampMs = parseExifDate("${g[1]}:${g[2]}:${g[3]} ${g[4]}:${g[5]}:${g[6]}")
        }
    }

    if (isVideo && latLng == null) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val locStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
            if (locStr != null) {
                val match = Regex("([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)").find(locStr)
                if (match != null) {
                    val vLat = match.groupValues[1].toDoubleOrNull()
                    val vLng = match.groupValues[2].toDoubleOrNull()
                    if (vLat != null && vLng != null) {
                        latLng = Pair(vLat, vLng)
                    }
                }
            }
        } catch (e: Exception) {
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
            }
        }
    }

    return MediaEntry(
        id = "${uri}_${index}_${System.nanoTime()}",
        uriString = uri.toString(),
        displayName = name,
        isVideo = isVideo,
        lat = latLng?.first,
        lng = latLng?.second,
        timestampMs = timestampMs,
        caption = name
    )
}

private fun openOriginalStream(context: Context, uri: Uri): java.io.InputStream? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        try {
            val original = MediaStore.setRequireOriginal(uri)
            val stream = context.contentResolver.openInputStream(original)
            if (stream != null) return stream
        } catch (e: Exception) {
        }
    }
    return context.contentResolver.openInputStream(uri)
}

private fun parseExifDate(raw: String): Long? {
    val formats = listOf(
        "yyyy:MM:dd HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss"
    )
    for (fmt in formats) {
        try {
            val sdf = SimpleDateFormat(fmt, Locale.US)
            val parsed = sdf.parse(raw)
            if (parsed != null) return parsed.time
        } catch (e: Exception) {
        }
    }
    return null
}

private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return r * c
}

private fun MediaEntry.toJson(): JSONObject {
    return JSONObject().apply {
        put("id", id)
        put("uriString", uriString)
        put("displayName", displayName)
        put("isVideo", isVideo)
        if (lat != null) put("lat", lat) else put("lat", JSONObject.NULL)
        if (lng != null) put("lng", lng) else put("lng", JSONObject.NULL)
        if (timestampMs != null) put("timestampMs", timestampMs) else put("timestampMs", JSONObject.NULL)
        put("caption", caption)
    }
}

private fun mediaEntryFromJson(obj: JSONObject): MediaEntry {
    val latVal = if (obj.isNull("lat")) null else obj.optDouble("lat")
    val lngVal = if (obj.isNull("lng")) null else obj.optDouble("lng")
    val tsVal = if (obj.isNull("timestampMs")) null else obj.optLong("timestampMs")
    return MediaEntry(
        id = obj.optString("id", "item_${System.nanoTime()}"),
        uriString = obj.optString("uriString", ""),
        displayName = obj.optString("displayName", "media"),
        isVideo = obj.optBoolean("isVideo", false),
        lat = latVal,
        lng = lngVal,
        timestampMs = tsVal,
        caption = obj.optString("caption", "")
    )
}