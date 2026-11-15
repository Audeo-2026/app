package com.audeo.app.ui.audeo

import android.app.Activity
import android.graphics.PixelFormat
import android.graphics.Rect
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.audeo.app.audeo.AudeoUiState
import com.audeo.app.audeo.AudeoViewModel
import com.audeo.app.audeo.CallStatus
import com.audeo.app.audeo.Scene
import com.audeo.app.ui.theme.AudeoTheme
import com.audeo.app.ui.theme.Space
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 운전 중 진짜 내비 위에 떠 있는 오디.
 *
 * NaviHelper는 내비 지도를 우리 앱 안에 그려 주지 않고 내비 앱을 조종하기만 해서, 길안내 중에는 내비가 주 화면이에요.
 * Audeo 앱이 뒤로 가면(onStop) 앱 영역 왼쪽 아래에 오디 한 줄을 띄우고, 앱이 앞으로 오면(onStart) 내립니다.
 * 고를 일이 생기면 그 자리에서 펼쳐지고, 통화 중이면 통화 카드도 함께 보여요. 할 일이 없으면(처음 화면) 아무것도 그리지 않아요.
 * 다른 앱 위에 그리기 권한(SYSTEM_ALERT_WINDOW)이 필요합니다.
 */
class AssistantOverlay(private val activity: Activity, private val vm: AudeoViewModel) {

    private val wm = activity.getSystemService(WindowManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: Job? = null
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null

    /** 앱 영역(내비가 그려지는 자리). 앱이 보일 때 재 둡니다. */
    private var area: Rect? = null

    fun rememberArea() {
        val v = activity.window?.decorView ?: return
        if (v.width == 0 || v.height == 0) return
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        area = Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    /**
     * 앱이 뒤로 갔을 때 부릅니다. 보여 줄 게 있을 때만 창을 붙이고, 없어지면 창을 떼요
     * (크기 0인 창은 마지막 그림이 남을 수 있어서 아예 뗍니다). 전화가 오면 그때 붙어요.
     */
    fun show() {
        if (watch != null) return
        if (!Settings.canDrawOverlays(activity)) {
            vm.onOverlayDenied()
            return
        }
        watch = scope.launch {
            vm.state.map(::wanted).distinctUntilChanged().collect { if (it) attach() else detach() }
        }
    }

    /** 앱이 앞으로 왔을 때 부릅니다. */
    fun hide() {
        watch?.cancel()
        watch = null
        detach()
    }

    private fun wanted(s: AudeoUiState) =
        !(s.scene == Scene.START && (s.call == null || s.call?.status == CallStatus.ENDED))

    private fun attach() {
        if (view != null) return
        val a = area
        val screen = wm.maximumWindowMetrics.bounds
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 포커스를 가져가지 않아 창 밖의 터치는 그대로 내비로 갑니다.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = a?.left ?: 0
            y = if (a != null) (screen.bottom - a.bottom).coerceAtLeast(0) else 0
            title = "Audeo 오디"
        }
        val o = OverlayOwner().also { it.start() }
        val maxWidthPx = a?.width()
        val cv = ComposeView(activity).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent { OverlayAssistant(vm, maxWidthPx) }
        }
        runCatching { wm.addView(cv, lp) }.onFailure {
            o.stop()
            vm.onOverlayDenied()
            return
        }
        view = cv
        owner = o
    }

    private fun detach() {
        view?.let { runCatching { wm.removeViewImmediate(it) } }
        owner?.stop()
        view = null
        owner = null
    }
}

@Composable
private fun OverlayAssistant(vm: AudeoViewModel, maxWidthPx: Int?) {
    val s by vm.state.collectAsState()
    val talking by vm.talking.collectAsState()
    val maxWidth = with(LocalDensity.current) { maxWidthPx?.toDp() } ?: SHEET_MAX_W
    AudeoTheme(night = s.nightOverride ?: isSystemInDarkTheme()) {
        Column(
            // 위 여백: 오디가 시트 위로 올라오고 '접기' 손잡이가 걸치는 자리
            Modifier
                .widthIn(max = maxWidth)
                .padding(start = NAVI_LEFT_PANEL, end = Space.s3, bottom = Space.s3, top = ODI_RISE + Space.s2),
            verticalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            var finderShown by remember { mutableStateOf(false) }
            finderShown = showPassengerFinder(s, finderShown)
            AnimatedVisibility(visible = finderShown, enter = Motion.barEnter(), exit = Motion.barExit()) {
                PassengerFinder(s, Modifier.width(460.dp))
            }
            val liveCall = s.call?.takeIf { it.status != CallStatus.ENDED }
            val shownCall = rememberLast(liveCall)
            AnimatedVisibility(visible = liveCall != null, enter = Motion.barEnter(), exit = Motion.barExit()) {
                val call = shownCall ?: return@AnimatedVisibility
                CallCard(call, s, talking, vm)
            }
            // 통화 카드 아래로 오디가 올라올 자리
            if (liveCall != null) Spacer(Modifier.height(ODI_RISE))
            AssistantDock(s, vm, SHEET_MAX_H)
        }
    }
}

/**
 * Pleos 내비는 왼쪽 아래에 도착 시각·'안내 종료' 패널을, 왼쪽 위에 회전 안내를 둡니다.
 * 오디는 그 패널 오른쪽에 떠서 내비 정보를 가리지 않게 합니다(펼친 시트도 앱 영역 안에 들어가요).
 */
private val NAVI_LEFT_PANEL = 620.dp

/** 활동(Activity) 밖 창에서 Compose를 돌리기 위한 생명주기·상태 저장소. 창이 떠 있는 동안 RESUMED. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    fun start() {
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun stop() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
