package com.example.travelcinema

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
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

data class MediaItemMeta(
    val uriString: String,
    val displayName: String,
    val isVideo: Boolean,
    val timestamp: String,
    val lat: Double?,
    val lng: Double?,
    val hasTime: Boolean,
    val hasGps: Boolean
)

data class TripStop(
    val order: Int,
    val date: String,
    val time: String,
    val customPlace: String = "",
    val geoPlace: String = "",
    val place: String,
    val lat: Double?,
    val lng: Double?,
    val items: List<MediaItemMeta>
) {
    fun effectivePlace(index: Int = order - 1): String {
        if (customPlace.isNotBlank()) return customPlace.trim()
        if (geoPlace.isNotBlank()) return geoPlace.trim()
        if (place.isNotBlank() && !place.startsWith("지점 #") && !place.startsWith("방문지 #")) {
            return place.trim()
        }
        return place.ifBlank { "지점 #${index + 1}" }
    }
}

data class SavedTripSummary(
    val folderName: String,
    val title: String,
    val exportedAt: String,
    val stopCount: Int,
    val mediaCount: Int,
    val hasBgm: Boolean
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                TravelCinemaApp()
            }
        }
    }
}

@Composable
fun TravelCinemaApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tripTitle by remember { mutableStateOf("나의 여행 기록") }
    var resolution by remember { mutableStateOf("1080p") }
    var bgmUri by remember { mutableStateOf<Uri?>(null) }
    var routeMapUri by remember { mutableStateOf<Uri?>(null) }
    var statusText by remember { mutableStateOf("사진/동영상을 선택해 여행 일정을 만들어보세요.") }
    var isBusy by remember { mutableStateOf(false) }

    var stops by remember { mutableStateOf<List<TripStop>>(emptyList()) }
    var unassigned by remember { mutableStateOf<List<MediaItemMeta>>(emptyList()) }
    var savedTrips by remember { mutableStateOf<List<SavedTripSummary>>(emptyList()) }

    // 미분류 항목 개별 위치 지정 다이얼로그 + 지도 탭 지정 모드 상태
    var editingUnassigned by remember { mutableStateOf<MediaItemMeta?>(null) }
    var assignToExisting by remember { mutableStateOf(true) }
    var selectedExistingIndex by remember { mutableIntStateOf(0) }
    var newStopPlaceName by remember { mutableStateOf("") }
    var pickedMapLatLng by remember { mutableStateOf<LatLng?>(null) }
    var isPickingOnMap by remember { mutableStateOf(false) }

    // 저장된 오프라인 여행 기록 목록 화면 표시 여부
    var showSavedTripsDialog by remember { mutableStateOf(false) }

    // Google Maps 카메라 상태
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(36.5446, 127.8356), 7f)
    }

    fun refreshSavedTrips() {
        savedTrips = listSavedOfflineTripsAndroid(context)
    }

    LaunchedEffect(Unit) {
        refreshSavedTrips()
    }

    // 방문지 좌표가 갱신되면 전체 경로가 보이도록 카메라 이동
    LaunchedEffect(stops) {
        val coords = stops.mapNotNull { s ->
            if (s.lat != null && s.lng != null) LatLng(s.lat, s.lng) else null
        }
        if (coords.size == 1) {
            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(coords.first(), 13f))
        } else if (coords.size > 1) {
            val builder = LatLngBounds.Builder()
            coords.forEach { builder.include(it) }
            try {
                cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(builder.build(), 100))
            } catch (_: Exception) {
                cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(coords.first(), 10f))
            }
        }
    }

    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            isBusy = true
            statusText = "선택한 ${uris.size}개 미디어의 EXIF·GPS 분석 중..."
            val (groupedStops, unplaced) = withContext(Dispatchers.IO) {
                val metas = uris.map { extractAndroidMediaMeta(context, it) }
                groupStopsAndroid(context, metas, radiusMeters = 200.0)
            }
            stops = groupedStops
            unassigned = unplaced
            statusText = "분석 완료: 방문지 ${groupedStops.size}곳, 미분류 ${unplaced.size}개"
            isBusy = false
        }
    }

    val bgmPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            bgmUri = uri
            statusText = "배경음악 선택 완료: ${uri.lastPathSegment ?: "오디오"}"
        }
    }

    val routeMapPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            routeMapUri = uri
            statusText = "전체 경로 캡처 사진 선택 완료"
        }
    }

    fun reindexStops(list: List<TripStop>): List<TripStop> =
        list.mapIndexed { idx, s -> s.copy(order = idx + 1, place = s.effectivePlace(idx)) }

    fun moveItemInStop(stopIndex: Int, itemIndex: Int, delta: Int) {
        val currentStops = stops.toMutableList()
        val targetStop = currentStops.getOrNull(stopIndex) ?: return
        val nextIndex = itemIndex + delta
        if (nextIndex !in targetStop.items.indices) return

        val mutableItems = targetStop.items.toMutableList()
        val tmp = mutableItems[itemIndex]
        mutableItems[itemIndex] = mutableItems[nextIndex]
        mutableItems[nextIndex] = tmp

        currentStops[stopIndex] = targetStop.copy(items = mutableItems)
        stops = reindexStops(currentStops)
        statusText = "[${targetStop.effectivePlace(stopIndex)}] 재생 순서 변경: ${tmp.displayName}"
    }

    fun deleteItemFromStop(stopIndex: Int, itemIndex: Int) {
        val currentStops = stops.toMutableList()
        val targetStop = currentStops.getOrNull(stopIndex) ?: return
        if (itemIndex !in targetStop.items.indices) return

        val mutableItems = targetStop.items.toMutableList()
        val removed = mutableItems.removeAt(itemIndex)
        currentStops[stopIndex] = targetStop.copy(items = mutableItems)
        stops = reindexStops(currentStops)
        statusText = "[${targetStop.effectivePlace(stopIndex)}] 항목 삭제됨: ${removed.displayName}"
    }

    fun applyPickedMapLocationToUnassigned(latLng: LatLng) {
        val targetItem = editingUnassigned ?: return
        scope.launch {
            isPickingOnMap = false
            val geoName = withContext(Dispatchers.IO) {
                reverseGeocodeAndroid(context, latLng.latitude, latLng.longitude)
            }
            val custom = newStopPlaceName.trim()
            val finalPlace = custom.ifBlank { geoName.ifBlank { "지점 #${stops.size + 1}" } }
            val parts = targetItem.timestamp.split(" ")
            val datePart = parts.firstOrNull() ?: "2026-01-01"
            val timePart = parts.getOrNull(1)?.take(5) ?: "00:00"

            val newStop = TripStop(
                order = stops.size + 1,
                date = datePart,
                time = timePart,
                customPlace = custom,
                geoPlace = geoName,
                place = finalPlace,
                lat = latLng.latitude,
                lng = latLng.longitude,
                items = listOf(
                    targetItem.copy(
                        lat = latLng.latitude,
                        lng = latLng.longitude,
                        hasGps = true
                    )
                )
            )
            stops = reindexStops(stops + newStop)
            unassigned = unassigned.filterNot { it === targetItem }
            editingUnassigned = null
            pickedMapLatLng = null
            statusText = "지도 탭 위치 반영 완료: '$finalPlace' (${String.format("%.5f", latLng.latitude)}, ${String.format("%.5f", latLng.longitude)})"
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Text("여행 기록 & 영상 제작 스튜디오", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))

        OutlinedTextField(
            value = tripTitle,
            onValueChange = { tripTitle = it },
            label = { Text("여행 제목") },
            enabled = !isBusy,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                enabled = !isBusy,
                onClick = {
                    mediaPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                    )
                }
            ) { Text("사진·영상") }

            Button(
                enabled = !isBusy,
                onClick = { routeMapPicker.launch("image/*") }
            ) { Text("경로 사진") }

            Button(
                enabled = !isBusy,
                onClick = { bgmPicker.launch("audio/*") }
            ) { Text("BGM") }

            OutlinedButton(
                enabled = !isBusy,
                onClick = { resolution = if (resolution == "1080p") "720p" else "1080p" }
            ) { Text(resolution) }
        }

        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                enabled = !isBusy,
                onClick = {
                    if (stops.isEmpty() && unassigned.isEmpty()) {
                        statusText = "먼저 사진/동영상을 분석하거나 기록을 불러오세요."
                        return@Button
                    }
                    val savedDir = saveOfflineTripBundleAndroid(context, tripTitle, reindexStops(stops), bgmUri)
                    refreshSavedTrips()
                    statusText = "오프라인 기록 저장 완료: ${savedDir.name}"
                }
            ) { Text("오프라인 저장") }

            OutlinedButton(
                enabled = !isBusy,
                onClick = {
                    refreshSavedTrips()
                    showSavedTripsDialog = true
                }
            ) { Text("저장 기록(${savedTrips.size})") }

            Button(
                enabled = !isBusy,
                modifier = Modifier.weight(1f),
                onClick = {
                    if (stops.isEmpty()) {
                        statusText = "먼저 사진/동영상을 분석하거나 위치가 지정된 방문지를 만드세요."
                        return@Button
                    }
                    scope.launch {
                        isBusy = true
                        val result = withContext(Dispatchers.IO) {
                            VideoRenderer.renderTripVideoToGallery(
                                context = context,
                                tripTitle = tripTitle,
                                resolution = resolution,
                                stops = reindexStops(stops),
                                routeMapUri = routeMapUri,
                                bgmUri = bgmUri,
                                onProgress = { msg -> statusText = msg }
                            )
                        }
                        statusText = result.fold(
                            onSuccess = { it },
                            onFailure = { "MP4 생성 실패: ${it.message}" }
                        )
                        isBusy = false
                    }
                }
            ) { Text("MP4 생성") }
        }

        Spacer(Modifier.height(6.dp))
        Text(statusText, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))

        // Google Maps Compose 지도 영역 (방문지 핀 + 경로선 + 지도 탭 새 위치 지정)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(230.dp)
        ) {
            val routePoints = stops.mapNotNull { s ->
                if (s.lat != null && s.lng != null) LatLng(s.lat, s.lng) else null
            }

            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                onMapClick = { latLng ->
                    if (isPickingOnMap && editingUnassigned != null) {
                        pickedMapLatLng = latLng
                        applyPickedMapLocationToUnassigned(latLng)
                    }
                }
            ) {
                if (routePoints.size > 1) {
                    Polyline(
                        points = routePoints,
                        color = Color(0xFFD4AF37),
                        width = 10f
                    )
                }

                stops.forEachIndexed { idx, s ->
                    if (s.lat != null && s.lng != null) {
                        Marker(
                            state = MarkerState(position = LatLng(s.lat, s.lng)),
                            title = "${s.order}. ${s.effectivePlace(idx)}",
                            snippet = "${s.date} ${s.time} · 자료 ${s.items.size}개"
                        )
                    }
                }

                pickedMapLatLng?.let { tempPt ->
                    Marker(
                        state = MarkerState(position = tempPt),
                        title = "선택된 새 방문지 위치"
                    )
                }
            }

            if (isPickingOnMap) {
                Surface(
                    color = Color(0xE6020617),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "지도를 탭해 새 방문지 위치를 찍으세요",
                            color = Color(0xFFFFF8E7),
                            style = MaterialTheme.typography.labelMedium
                        )
                        TextButton(
                            onClick = {
                                isPickingOnMap = false
                                pickedMapLatLng = null
                            }
                        ) {
                            Text("취소", color = Color(0xFFD4AF37))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (unassigned.isNotEmpty()) {
                item {
                    Text(
                        "위치·시간 미확인 자료 (${unassigned.size}개) — 지도 탭 지정 가능",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                itemsIndexed(unassigned, key = { idx, item -> "un_${idx}_${item.uriString}" }) { idx, item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            val kind = if (item.isVideo) "동영상" else "사진"
                            Text("[$kind] ${item.displayName}", style = MaterialTheme.typography.bodyMedium)
                            Text("일시: ${item.timestamp}", style = MaterialTheme.typography.bodySmall)
                            Row(
                                modifier = Modifier.padding(top = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    enabled = !isBusy,
                                    onClick = {
                                        editingUnassigned = item
                                        assignToExisting = stops.isNotEmpty()
                                        selectedExistingIndex = 0
                                        newStopPlaceName = ""
                                        pickedMapLatLng = null
                                    }
                                ) { Text("위치/방문지 지정") }

                                TextButton(
                                    enabled = !isBusy,
                                    onClick = {
                                        unassigned = unassigned.filterIndexed { i, _ -> i != idx }
                                        statusText = "미분류 항목 삭제됨: ${item.displayName}"
                                    }
                                ) { Text("삭제") }
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "방문지 일정 (${stops.size}곳) — 이름 수정, 방문지·개별 미디어 순서 변경 및 삭제",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            itemsIndexed(stops, key = { idx, stop -> "stop_${idx}_${stop.date}_${stop.time}" }) { index, stop ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "${stop.order}. ${stop.effectivePlace(index)} (${stop.date} ${stop.time})",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            "자료 ${stop.items.size}개 | 좌표: ${stop.lat ?: "-"}, ${stop.lng ?: "-"}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = stop.customPlace,
                            onValueChange = { typed ->
                                val mutable = stops.toMutableList()
                                mutable[index] = stop.copy(
                                    customPlace = typed,
                                    place = typed.trim().ifBlank {
                                        stop.geoPlace.ifBlank { "지점 #${index + 1}" }
                                    }
                                )
                                stops = reindexStops(mutable)
                            },
                            label = { Text("방문지 이름 직접 수정 (비우면 자동 지명)") },
                            placeholder = { Text(stop.geoPlace.ifBlank { "예: 속리산 법주사" }) },
                            enabled = !isBusy,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextButton(
                                enabled = !isBusy && index > 0,
                                onClick = {
                                    val mutable = stops.toMutableList()
                                    val tmp = mutable[index]
                                    mutable[index] = mutable[index - 1]
                                    mutable[index - 1] = tmp
                                    stops = reindexStops(mutable)
                                }
                            ) { Text("▲ 위로") }

                            TextButton(
                                enabled = !isBusy && index < stops.lastIndex,
                                onClick = {
                                    val mutable = stops.toMutableList()
                                    val tmp = mutable[index]
                                    mutable[index] = mutable[index + 1]
                                    mutable[index + 1] = tmp
                                    stops = reindexStops(mutable)
                                }
                            ) { Text("▼ 아래로") }
                        }

                        if (stop.items.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            Text(
                                "재생 순서 목록 (${stop.items.size}개)",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Spacer(Modifier.height(4.dp))
                            stop.items.forEachIndexed { itemIdx, media ->
                                val badge = if (media.isVideo) "동영상" else "사진"
                                Surface(
                                    tonalElevation = 2.dp,
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                "${itemIdx + 1}. [$badge] ${media.displayName}",
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 1
                                            )
                                            if (media.timestamp.isNotBlank()) {
                                                Text(
                                                    media.timestamp,
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                            TextButton(
                                                enabled = !isBusy && itemIdx > 0,
                                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                                onClick = { moveItemInStop(index, itemIdx, -1) }
                                            ) { Text("▲") }
                                            TextButton(
                                                enabled = !isBusy && itemIdx < stop.items.lastIndex,
                                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                                onClick = { moveItemInStop(index, itemIdx, 1) }
                                            ) { Text("▼") }
                                            TextButton(
                                                enabled = !isBusy,
                                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                                onClick = { deleteItemFromStop(index, itemIdx) }
                                            ) { Text("삭제") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 미분류 사진/동영상 개별 위치 지정 다이얼로그
    val targetItem = editingUnassigned
    if (targetItem != null && !isPickingOnMap) {
        AlertDialog(
            onDismissRequest = { editingUnassigned = null },
            title = { Text("개별 위치 지정: ${targetItem.displayName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = assignToExisting,
                            enabled = stops.isNotEmpty(),
                            onClick = { assignToExisting = true },
                            label = { Text("기존 방문지") }
                        )
                        FilterChip(
                            selected = !assignToExisting,
                            onClick = { assignToExisting = false },
                            label = { Text("지도 탭 새 위치") }
                        )
                    }

                    if (assignToExisting && stops.isNotEmpty()) {
                        stops.forEachIndexed { idx, s ->
                            val selected = idx == selectedExistingIndex
                            OutlinedButton(
                                onClick = { selectedExistingIndex = idx },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    (if (selected) "[선택됨] " else "") +
                                        "${idx + 1}. ${s.effectivePlace(idx)} (${s.date} ${s.time})"
                                )
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = newStopPlaceName,
                            onValueChange = { newStopPlaceName = it },
                            label = { Text("새 방문지 이름 (비우면 지도 지명 자동)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = { isPickingOnMap = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("지도 화면에서 직접 탭해 위치 찍기")
                        }
                    }
                }
            },
            confirmButton = {
                if (assignToExisting && stops.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            val idx = selectedExistingIndex.coerceIn(0, stops.lastIndex)
                            val targetStop = stops[idx]
                            val assigned = targetItem.copy(
                                lat = targetStop.lat,
                                lng = targetStop.lng,
                                hasGps = targetStop.lat != null && targetStop.lng != null
                            )
                            val updatedStops = stops.toMutableList()
                            updatedStops[idx] = targetStop.copy(items = targetStop.items + assigned)
                            stops = reindexStops(updatedStops)
                            unassigned = unassigned.filterNot { it === targetItem }
                            statusText = "'${targetItem.displayName}' → '${targetStop.effectivePlace(idx)}' 반영 완료"
                            editingUnassigned = null
                        }
                    ) { Text("적용") }
                }
            },
            dismissButton = {
                TextButton(onClick = { editingUnassigned = null }) { Text("취소") }
            }
        )
    }

    // 저장된 오프라인 여행 기록 목록 화면 다이얼로그
    if (showSavedTripsDialog) {
        AlertDialog(
            onDismissRequest = { showSavedTripsDialog = false },
            title = { Text("저장된 오프라인 여행 기록 (${savedTrips.size}건)") },
            text = {
                if (savedTrips.isEmpty()) {
                    Text("저장된 여행 기록이 없습니다. '오프라인 저장' 버튼을 눌러 저장해보세요.")
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(savedTrips, key = { it.folderName }) { summary ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(summary.title, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "방문지 ${summary.stopCount}곳 · 자료 ${summary.mediaCount}개" +
                                            if (summary.hasBgm) " · BGM 포함" else "",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    if (summary.exportedAt.isNotBlank()) {
                                        Text("저장일시: ${summary.exportedAt}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Row(
                                        modifier = Modifier.padding(top = 6.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                val loaded = loadOfflineTripBundleAndroid(context, summary.folderName)
                                                if (loaded != null) {
                                                    tripTitle = loaded.first
                                                    stops = reindexStops(loaded.second)
                                                    bgmUri = loaded.third
                                                    unassigned = emptyList()
                                                    statusText = "'${loaded.first}' 기록 불러오기 완료 (${stops.size}곳)"
                                                } else {
                                                    statusText = "기록 파일을 읽지 못했습니다: ${summary.folderName}"
                                                }
                                                showSavedTripsDialog = false
                                            }
                                        ) { Text("불러오기") }

                                        TextButton(
                                            onClick = {
                                                deleteOfflineTripBundleAndroid(context, summary.folderName)
                                                refreshSavedTrips()
                                                statusText = "삭제 완료: ${summary.title}"
                                            }
                                        ) { Text("삭제") }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSavedTripsDialog = false }) { Text("닫기") }
            }
        )
    }
}

private fun extractAndroidMediaMeta(context: Context, uri: Uri): MediaItemMeta {
    val mime = context.contentResolver.getType(uri) ?: ""
    val isVideo = mime.startsWith("video/")
    val displayName = uri.lastPathSegment ?: "media_${System.currentTimeMillis()}"
    var lat: Double? = null
    var lng: Double? = null
    var hasTime = false
    var tsString = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date())

    if (!isVideo) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val exif = ExifInterface(input)
            val latLong = exif.latLong
            if (latLong != null) {
                lat = latLong[0]
                lng = latLong[1]
            }
            val dt = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            if (!dt.isNullOrBlank()) {
                tsString = dt.replaceFirst(":", "-").replaceFirst(":", "-")
                hasTime = true
            }
        }
    } else {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val dateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            if (!dateStr.isNullOrBlank()) {
                tsString = dateStr.take(19).replace("T", " ")
                hasTime = true
            }
        } catch (_: Exception) {
        } finally {
            retriever.release()
        }
    }

    return MediaItemMeta(
        uriString = uri.toString(),
        displayName = displayName,
        isVideo = isVideo,
        timestamp = tsString,
        lat = lat,
        lng = lng,
        hasTime = hasTime,
        hasGps = lat != null && lng != null
    )
}

private fun groupStopsAndroid(
    context: Context,
    items: List<MediaItemMeta>,
    radiusMeters: Double
): Pair<List<TripStop>, List<MediaItemMeta>> {
    val valid = items.filter { it.hasTime && it.hasGps }.sortedBy { it.timestamp }
    val invalid = items.filterNot { it.hasTime && it.hasGps }.sortedBy { it.timestamp }
    val result = mutableListOf<TripStop>()

    for (item in valid) {
        val parts = item.timestamp.split(" ")
        val datePart = parts.firstOrNull() ?: "2026-01-01"
        val timePart = parts.getOrNull(1)?.take(5) ?: "00:00"
        val prev = result.lastOrNull()

        var merged = false
        if (prev != null && prev.date == datePart && prev.lat != null && prev.lng != null && item.lat != null && item.lng != null) {
            val distArr = FloatArray(1)
            Location.distanceBetween(prev.lat, prev.lng, item.lat, item.lng, distArr)
            if (distArr[0] <= radiusMeters) {
                result[result.lastIndex] = prev.copy(items = prev.items + item)
                merged = true
            }
        }

        if (!merged) {
            val geoName = reverseGeocodeAndroid(context, item.lat, item.lng)
            val label = geoName.ifBlank { "지점 #${result.size + 1}" }
            result.add(
                TripStop(
                    order = result.size + 1,
                    date = datePart,
                    time = timePart,
                    geoPlace = geoName,
                    place = label,
                    lat = item.lat,
                    lng = item.lng,
                    items = listOf(item)
                )
            )
        }
    }
    return result to invalid
}

private fun reverseGeocodeAndroid(context: Context, lat: Double?, lng: Double?): String {
    if (lat == null || lng == null) return ""
    return try {
        @Suppress("DEPRECATION")
        val geocoder = Geocoder(context, Locale.KOREA)
        @Suppress("DEPRECATION")
        val list = geocoder.getFromLocation(lat, lng, 1)
        val addr = list?.firstOrNull()
        listOfNotNull(addr?.locality, addr?.subLocality, addr?.thoroughfare).joinToString(" ").trim()
    } catch (_: Exception) {
        ""
    }
}

private fun getShelfRootAndroid(context: Context): File =
    File(context.getExternalFilesDir(null) ?: context.filesDir, "나의 여행기록목록").apply { mkdirs() }

private fun saveOfflineTripBundleAndroid(
    context: Context,
    title: String,
    stops: List<TripStop>,
    bgmUri: Uri?
): File {
    val safeName = title.replace(Regex("""[\\/:*?"<>|]+"""), "_").trim().ifBlank { "나의_여행기록" }
    val root = getShelfRootAndroid(context)
    val tripDir = File(root, safeName).apply { mkdirs() }

    val exportedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date())
    val rootJson = JSONObject()
    rootJson.put("title", title)
    rootJson.put("exported_at", exportedAt)
    rootJson.put("bgm_uri", bgmUri?.toString() ?: "")

    val stopsArr = JSONArray()
    stops.forEachIndexed { idx, s ->
        val obj = JSONObject()
        obj.put("order", idx + 1)
        obj.put("date", s.date)
        obj.put("time", s.time)
        obj.put("custom_place", s.customPlace)
        obj.put("geo_place", s.geoPlace)
        obj.put("place", s.effectivePlace(idx))
        obj.put("lat", s.lat ?: JSONObject.NULL)
        obj.put("lng", s.lng ?: JSONObject.NULL)

        val itemsArr = JSONArray()
        s.items.forEach { it ->
            itemsArr.put(
                JSONObject()
                    .put("uri", it.uriString)
                    .put("filename", it.displayName)
                    .put("is_video", it.isVideo)
                    .put("timestamp", it.timestamp)
                    .put("lat", it.lat ?: JSONObject.NULL)
                    .put("lng", it.lng ?: JSONObject.NULL)
                    .put("has_time", it.hasTime)
                    .put("has_gps", it.hasGps)
            )
        }
        obj.put("items", itemsArr)
        stopsArr.put(obj)
    }
    rootJson.put("stops", stopsArr)
    File(tripDir, "trip_data.json").writeText(rootJson.toString(2), Charsets.UTF_8)
    return tripDir
}

private fun listSavedOfflineTripsAndroid(context: Context): List<SavedTripSummary> {
    val root = getShelfRootAndroid(context)
    val dirs = root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() } ?: return emptyList()
    return dirs.mapNotNull { dir ->
        val jsonFile = File(dir, "trip_data.json")
        if (!jsonFile.exists()) return@mapNotNull null
        try {
            val obj = JSONObject(jsonFile.readText(Charsets.UTF_8))
            val title = obj.optString("title", dir.name).ifBlank { dir.name }
            val exportedAt = obj.optString("exported_at", "")
            val hasBgm = obj.optString("bgm_uri", "").isNotBlank()
            val stopsArr = obj.optJSONArray("stops") ?: JSONArray()
            var mediaCount = 0
            for (i in 0 until stopsArr.length()) {
                val st = stopsArr.optJSONObject(i) ?: continue
                mediaCount += (st.optJSONArray("items")?.length() ?: 0)
            }
            SavedTripSummary(
                folderName = dir.name,
                title = title,
                exportedAt = exportedAt,
                stopCount = stopsArr.length(),
                mediaCount = mediaCount,
                hasBgm = hasBgm
            )
        } catch (_: Exception) {
            null
        }
    }
}

private fun loadOfflineTripBundleAndroid(
    context: Context,
    folderName: String
): Triple<String, List<TripStop>, Uri?>? {
    val jsonFile = File(getShelfRootAndroid(context), "$folderName/trip_data.json")
    if (!jsonFile.exists()) return null
    return try {
        val root = JSONObject(jsonFile.readText(Charsets.UTF_8))
        val title = root.optString("title", folderName).ifBlank { folderName }
        val bgmStr = root.optString("bgm_uri", "")
        val bgm = if (bgmStr.isNotBlank()) Uri.parse(bgmStr) else null

        val stopsArr = root.optJSONArray("stops") ?: JSONArray()
        val loadedStops = mutableListOf<TripStop>()
        for (i in 0 until stopsArr.length()) {
            val st = stopsArr.optJSONObject(i) ?: continue
            val lat = if (st.isNull("lat")) null else st.optDouble("lat")
            val lng = if (st.isNull("lng")) null else st.optDouble("lng")
            val customPlace = st.optString("custom_place", "")
            val geoPlace = st.optString("geo_place", "")
            val place = st.optString("place", "지점 #${i + 1}")

            val itemsArr = st.optJSONArray("items") ?: JSONArray()
            val items = mutableListOf<MediaItemMeta>()
            for (j in 0 until itemsArr.length()) {
                val it = itemsArr.optJSONObject(j) ?: continue
                val uriStr = it.optString("uri", "")
                if (uriStr.isBlank()) continue
                val iLat = if (it.isNull("lat")) lat else it.optDouble("lat")
                val iLng = if (it.isNull("lng")) lng else it.optDouble("lng")
                items.add(
                    MediaItemMeta(
                        uriString = uriStr,
                        displayName = it.optString("filename", "media_$j"),
                        isVideo = it.optBoolean("is_video", false),
                        timestamp = it.optString("timestamp", ""),
                        lat = iLat,
                        lng = iLng,
                        hasTime = it.optBoolean("has_time", true),
                        hasGps = it.optBoolean("has_gps", iLat != null && iLng != null)
                    )
                )
            }
            loadedStops.add(
                TripStop(
                    order = i + 1,
                    date = st.optString("date", ""),
                    time = st.optString("time", ""),
                    customPlace = customPlace,
                    geoPlace = geoPlace,
                    place = place,
                    lat = lat,
                    lng = lng,
                    items = items
                )
            )
        }
        Triple(title, loadedStops, bgm)
    } catch (_: Exception) {
        null
    }
}

private fun deleteOfflineTripBundleAndroid(context: Context, folderName: String) {
    File(getShelfRootAndroid(context), folderName).deleteRecursively()
}