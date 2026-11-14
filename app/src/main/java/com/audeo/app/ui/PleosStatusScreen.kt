package com.audeo.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.audeo.app.MainUiState
import com.audeo.app.MainViewModel
import com.audeo.app.pleos.NaviTest
import com.audeo.app.pleos.PleosSdk
import com.audeo.app.pleos.ServiceStatus

@Composable
fun PleosStatusScreen(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Surface(modifier = Modifier.fillMaxSize()) {
        PleosStatusContent(state = state, onRefresh = viewModel::refresh, onNaviTest = viewModel::runNaviTest)
    }
}

@Composable
private fun PleosStatusContent(
    state: MainUiState,
    onRefresh: () -> Unit,
    onNaviTest: (NaviTest) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Row(
                modifier = Modifier.widthIn(max = 960.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Audeo", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Pleos Connect SDK 연결 상태",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = onRefresh) { Text("다시 확인") }
            }
        }
        item { NaviTestCard(events = state.naviEvents, onNaviTest = onNaviTest) }
        items(state.services, key = { it.sdk }) { status ->
            val liveResult = when (status.sdk) {
                PleosSdk.VEHICLE -> state.vehicleResult
                PleosSdk.NAVI_HELPER -> state.naviResult
                else -> null
            }
            ServiceCard(status, liveResult)
        }
    }
}

@Composable
private fun ServiceCard(status: ServiceStatus, liveResult: String?) {
    Card(modifier = Modifier.widthIn(max = 960.dp).fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(status.sdk.label, style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = status.stateLabel(),
                    style = MaterialTheme.typography.titleMedium,
                    color = status.stateColor(),
                )
            }
            Text(
                text = "SDK ${status.sdkVersion ?: "?"} · 서비스 ${status.serviceVersion ?: "없음"}" +
                    (status.minServiceVersion?.let { " (최소 $it)" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            status.servicePackage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            liveResult?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun NaviTestCard(events: List<String>, onNaviTest: (NaviTest) -> Unit) {
    Card(modifier = Modifier.widthIn(max = 960.dp).fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("내비 화면 시험", style = MaterialTheme.typography.titleLarge)
            NaviTest.entries.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { test ->
                        OutlinedButton(onClick = { onNaviTest(test) }) { Text(test.label) }
                    }
                }
            }
            if (events.isEmpty()) {
                Text("버튼을 누르면 요청과 내비 이벤트가 여기에 쌓입니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            events.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

private fun ServiceStatus.stateLabel(): String = when (state) {
    ServiceStatus.State.READY -> "사용 가능"
    ServiceStatus.State.OUTDATED -> "서비스 업데이트 필요"
    ServiceStatus.State.MISSING -> "서비스 없음"
}

@Composable
private fun ServiceStatus.stateColor(): Color = when (state) {
    ServiceStatus.State.READY -> Color(0xFF4CAF50)
    ServiceStatus.State.OUTDATED -> Color(0xFFFFA000)
    ServiceStatus.State.MISSING -> MaterialTheme.colorScheme.error
}
