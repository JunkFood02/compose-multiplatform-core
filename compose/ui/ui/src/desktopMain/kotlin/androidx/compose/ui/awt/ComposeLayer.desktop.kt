/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(ExperimentalComposeUiApi::class)

package androidx.compose.ui.awt

import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.ui.ComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.toPointerKeyboardModifiers
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.density
import androidx.compose.ui.window.layoutDirection
import java.awt.*
import java.awt.Cursor
import java.awt.event.*
import java.awt.event.KeyEvent
import java.awt.im.InputMethodRequests
import javax.accessibility.Accessible
import javax.swing.SwingUtilities
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler
import org.jetbrains.skia.Canvas
import org.jetbrains.skiko.*
import org.jetbrains.skiko.SkiaLayer

internal class ComposeLayer(
    private val skiaLayerAnalytics: SkiaLayerAnalytics
) {
    private var isDisposed = false

    internal val sceneAccessible = ComposeSceneAccessible(
        ownersProvider = { scene.owners.asReversed() },
        mainOwnerProvider = { scene.mainOwner }
    )

    private val _component = ComponentImpl()
    val component: SkiaLayer get() = _component

    @OptIn(ExperimentalComposeUiApi::class)
    private val coroutineExceptionHandler = object :
        AbstractCoroutineContextElement(CoroutineExceptionHandler), CoroutineExceptionHandler {
        override fun handleException(context: CoroutineContext, exception: Throwable) {
            exceptionHandler?.onException(exception) ?: throw exception
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    var exceptionHandler: WindowExceptionHandler? = null

    @OptIn(ExperimentalComposeUiApi::class)
    private fun catchExceptions(body: () -> Unit) {
        try {
            body()
        } catch (e: Throwable) {
            exceptionHandler?.onException(e) ?: throw e
        }
    }

    private val platform = object : Platform by Platform.Empty {
        override fun setPointerIcon(pointerIcon: PointerIcon) {
            _component.cursor = (pointerIcon as? AwtCursor)?.cursor ?: Cursor(Cursor.DEFAULT_CURSOR)
        }

        override fun accessibilityController(owner: SemanticsOwner) =
            AccessibilityControllerImpl(owner, _component, onFocusReceived = {
                _component.requestNativeFocusOnAccessible(it)
            })

        override val windowInfo = WindowInfoImpl()

        override val textInputService = PlatformInput(_component)

        override val layoutDirection: LayoutDirection
            get() = component.layoutDirection

        override val focusManager = object : FocusManager {
            override fun clearFocus(force: Boolean) {
                val root = component.rootPane
                root?.focusTraversalPolicy?.getDefaultComponent(root)?.requestFocusInWindow()
            }

            override fun moveFocus(focusDirection: FocusDirection): Boolean = when (focusDirection) {
                FocusDirection.Next -> {
                    val toFocus = _component.focusCycleRootAncestor?.let { root ->
                        val policy = root.focusTraversalPolicy
                        policy.getComponentAfter(root, _component)
                            ?: policy.getDefaultComponent(root)
                    }
                    val hasFocus = toFocus?.hasFocus() == true
                    !hasFocus && toFocus?.requestFocusInWindow(FocusEvent.Cause.TRAVERSAL_FORWARD) == true
                }
                FocusDirection.Previous -> {
                    val toFocus = _component.focusCycleRootAncestor?.let { root ->
                        val policy = root.focusTraversalPolicy
                        policy.getComponentBefore(root, _component)
                            ?: policy.getDefaultComponent(root)
                    }
                    val hasFocus = toFocus?.hasFocus() == true
                    !hasFocus && toFocus?.requestFocusInWindow(FocusEvent.Cause.TRAVERSAL_BACKWARD) == true
                }
                else -> false
            }
        }

        override fun requestFocusForOwner(): Boolean {
            return component.hasFocus() || component.requestFocusInWindow()
        }

        override val viewConfiguration = object : ViewConfiguration {
            override val longPressTimeoutMillis: Long = 500
            override val doubleTapTimeoutMillis: Long = 300
            override val doubleTapMinTimeMillis: Long = 40
            override val touchSlop: Float get() = with(_component.density) { 18.dp.toPx() }
        }
    }

    internal val scene = ComposeScene(
        MainUIDispatcher + coroutineExceptionHandler,
        platform,
        Density(1f),
        _component::needRedraw
    )

    private val density get() = _component.density.density

    /**
     * Keyboard modifiers state might be changed when window is not focused, so window doesn't
     * receive any key events.
     * This flag is set when window focus changes. Then we can rely on it when handling the
     * first movementEvent to get the actual keyboard modifiers state from it.
     * After window gains focus, the first motionEvent.metaState (after focus gained) is used
     * to update windowInfo.keyboardModifiers.
     *
     * TODO: needs to be set `true` when focus changes:
     * (Window focus change is implemented in JB fork, but not upstreamed yet).
     */
    private var keyboardModifiersRequireUpdate = false

    private inner class ComponentImpl :
        SkiaLayer(
            externalAccessibleFactory = { sceneAccessible },
            analytics = skiaLayerAnalytics
        ),
        Accessible, PlatformComponent {
        var currentInputMethodRequests: InputMethodRequests? = null

        private var window: Window? = null
        private var windowListener = object : WindowFocusListener {
            override fun windowGainedFocus(e: WindowEvent) = refreshWindowFocus()
            override fun windowLostFocus(e: WindowEvent) = refreshWindowFocus()
        }

        override fun addNotify() {
            super.addNotify()
            resetDensity()
            initContent()
            updateSceneSize()
            window = SwingUtilities.getWindowAncestor(this)
            window?.addWindowFocusListener(windowListener)
            refreshWindowFocus()
        }

        override fun removeNotify() {
            window?.removeWindowFocusListener(windowListener)
            window = null
            refreshWindowFocus()
            super.removeNotify()
        }

        override fun paint(g: Graphics) {
            resetDensity()
            super.paint(g)
        }

        override fun getInputMethodRequests() = currentInputMethodRequests

        override fun enableInput(inputMethodRequests: InputMethodRequests) {
            currentInputMethodRequests = inputMethodRequests
            enableInputMethods(true)
            val focusGainedEvent = FocusEvent(this.canvas, FocusEvent.FOCUS_GAINED)
            inputContext.dispatchEvent(focusGainedEvent)
        }

        override fun disableInput() {
            currentInputMethodRequests = null
        }

        override fun doLayout() {
            super.doLayout()
            updateSceneSize()
        }

        private fun updateSceneSize() {
            this@ComposeLayer.scene.constraints = Constraints(
                maxWidth = (width * density.density).toInt().coerceAtLeast(0),
                maxHeight = (height * density.density).toInt().coerceAtLeast(0)
            )
        }

        override fun getPreferredSize(): Dimension {
            return if (isPreferredSizeSet) super.getPreferredSize() else Dimension(
                (this@ComposeLayer.scene.contentSize.width / density.density).toInt(),
                (this@ComposeLayer.scene.contentSize.height / density.density).toInt()
            )
        }

        override val locationOnScreen: Point
            @Suppress("ACCIDENTAL_OVERRIDE") // KT-47743
            get() = super.getLocationOnScreen()

        override var density: Density = Density(1f)
            private set

        private fun resetDensity() {
            density = (this as SkiaLayer).density
            if (this@ComposeLayer.scene.density != density) {
                this@ComposeLayer.scene.density = density
                updateSceneSize()
            }
        }

        private fun refreshWindowFocus() {
            platform.windowInfo.isWindowFocused = window?.isFocused ?: false
            keyboardModifiersRequireUpdate = true
        }

        fun setCurrentKeyboardModifiers(modifiers: PointerKeyboardModifiers) {
            platform.windowInfo.keyboardModifiers = modifiers
        }
    }

    init {
        _component.skikoView = object : SkikoView {
            override val input: SkikoInput
                get() = object: SkikoInput {

                }

            override fun onRender(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
                catchExceptions {
                    scene.render(canvas, nanoTime)
                }
            }
        }

        _component.addInputMethodListener(object : InputMethodListener {
            override fun caretPositionChanged(event: InputMethodEvent?) {
                if (isDisposed) return
                // Which OSes and which input method could produce such events? We need to have some
                // specific cases in mind before implementing this
            }

            override fun inputMethodTextChanged(event: InputMethodEvent) {
                if (isDisposed) return
                catchExceptions {
                    platform.textInputService.inputMethodTextChanged(event)
                }
            }
        })

        _component.addFocusListener(object : FocusListener {
            override fun focusGained(e: FocusEvent) {
                // We don't reset focus for Compose when the component loses focus temporary.
                // Partially because we don't support restoring focus after clearing it.
                // Focus can be lost temporary when another window or popup takes focus.
                if (!e.isTemporary) {
                    scene.requestFocus()
                }
            }

            override fun focusLost(e: FocusEvent) {
                // We don't reset focus for Compose when the component loses focus temporary.
                // Partially because we don't support restoring focus after clearing it.
                // Focus can be lost temporary when another window or popup takes focus.
                if (!e.isTemporary) {
                    scene.releaseFocus()
                }
            }
        })

        _component.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) = Unit
            override fun mousePressed(event: MouseEvent) = onMouseEvent(event)
            override fun mouseReleased(event: MouseEvent) = onMouseEvent(event)
            override fun mouseEntered(event: MouseEvent) = onMouseEvent(event)
            override fun mouseExited(event: MouseEvent) = onMouseEvent(event)
        })
        _component.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(event: MouseEvent) = onMouseEvent(event)
            override fun mouseMoved(event: MouseEvent) = onMouseEvent(event)
        })
        _component.addMouseWheelListener { event ->
            onMouseWheelEvent(event)
        }
        _component.focusTraversalKeysEnabled = false
        _component.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) = onKeyEvent(event)
            override fun keyReleased(event: KeyEvent) = onKeyEvent(event)
            override fun keyTyped(event: KeyEvent) = onKeyEvent(event)
        })
    }

    private fun onMouseEvent(event: MouseEvent) = catchExceptions {
        // AWT can send events after the window is disposed
        if (isDisposed) return@catchExceptions
        if (keyboardModifiersRequireUpdate) {
            keyboardModifiersRequireUpdate = false
            _component.setCurrentKeyboardModifiers(event.keyboardModifiers)
        }
        scene.onMouseEvent(density, event)
    }

    private fun onMouseWheelEvent(event: MouseWheelEvent) = catchExceptions {
        if (isDisposed) return@catchExceptions
        scene.onMouseWheelEvent(density, event)
    }

    private fun onKeyEvent(event: KeyEvent) = catchExceptions {
        if (isDisposed) return@catchExceptions
        platform.textInputService.onKeyEvent(event)
        _component.setCurrentKeyboardModifiers(event.toPointerKeyboardModifiers())
        if (scene.sendKeyEvent(ComposeKeyEvent(event))) {
            event.consume()
        }
    }

    fun dispose() {
        check(!isDisposed)
        scene.close()
        _component.dispose()
        _initContent = null
        isDisposed = true
    }

    var compositionLocalContext: CompositionLocalContext? by scene::compositionLocalContext

    fun setContent(
        onPreviewKeyEvent: (ComposeKeyEvent) -> Boolean = { false },
        onKeyEvent: (ComposeKeyEvent) -> Boolean = { false },
        content: @Composable () -> Unit
    ) {
        // If we call it before attaching, everything probably will be fine,
        // but the first composition will be useless, as we set density=1
        // (we don't know the real density if we have unattached component)
        _initContent = {
            catchExceptions {
                scene.setContent(
                    onPreviewKeyEvent = onPreviewKeyEvent,
                    onKeyEvent = onKeyEvent,
                    content = content
                )
            }
        }
        initContent()
    }

    private var _initContent: (() -> Unit)? = null

    private fun initContent() {
        if (_component.isDisplayable) {
            _initContent?.invoke()
            _initContent = null
        }
    }
}

@Suppress("ControlFlowWithEmptyBody")
@OptIn(ExperimentalComposeUiApi::class)
private fun ComposeScene.onMouseEvent(
    density: Float,
    event: MouseEvent
) {
    val eventType = when (event.id) {
        MouseEvent.MOUSE_PRESSED -> PointerEventType.Press
        MouseEvent.MOUSE_RELEASED -> PointerEventType.Release
        MouseEvent.MOUSE_DRAGGED -> PointerEventType.Move
        MouseEvent.MOUSE_MOVED -> PointerEventType.Move
        MouseEvent.MOUSE_ENTERED -> PointerEventType.Enter
        MouseEvent.MOUSE_EXITED -> PointerEventType.Exit
        else -> PointerEventType.Unknown
    }
    sendPointerEvent(
        eventType = eventType,
        position = Offset(event.x.toFloat(), event.y.toFloat()) * density,
        timeMillis = event.`when`,
        type = PointerType.Mouse,
        buttons = event.buttons,
        keyboardModifiers = event.keyboardModifiers,
        nativeEvent = event,
        button = event.getPointerButton()
    )
}

@OptIn(ExperimentalComposeUiApi::class)
private fun MouseEvent.getPointerButton(): PointerButton? {
    if (button == MouseEvent.NOBUTTON) return null
    return when (button) {
        MouseEvent.BUTTON2 -> PointerButton.Tertiary
        MouseEvent.BUTTON3 -> PointerButton.Secondary
        else -> PointerButton(button - 1)
    }
}

@Suppress("ControlFlowWithEmptyBody")
@OptIn(ExperimentalComposeUiApi::class)
private fun ComposeScene.onMouseWheelEvent(
    density: Float,
    event: MouseWheelEvent
) {
    sendPointerEvent(
        eventType = PointerEventType.Scroll,
        position = Offset(event.x.toFloat(), event.y.toFloat()) * density,
        scrollDelta = if (event.isShiftDown) {
            Offset(event.preciseWheelRotation.toFloat(), 0f)
        } else {
            Offset(0f, event.preciseWheelRotation.toFloat())
        },
        timeMillis = event.`when`,
        type = PointerType.Mouse,
        buttons = event.buttons,
        keyboardModifiers = event.keyboardModifiers,
        nativeEvent = event
    )
}


@OptIn(ExperimentalComposeUiApi::class)
private val MouseEvent.buttons get() = PointerButtons(
    // We should check [event.button] because of case where [event.modifiersEx] does not provide
    // info about the pressed mouse button when using touchpad on MacOS 12 (AWT only).
    // When the [Tap to click] feature is activated on Mac OS 12, half of all clicks are not
    // handled because [event.modifiersEx] may not provide info about the pressed mouse button.
    isPrimaryPressed = ((modifiersEx and MouseEvent.BUTTON1_DOWN_MASK) != 0
        || (id == MouseEvent.MOUSE_PRESSED && button == MouseEvent.BUTTON1))
        && !isMacOsCtrlClick,
    isSecondaryPressed = (modifiersEx and MouseEvent.BUTTON3_DOWN_MASK) != 0
        || (id == MouseEvent.MOUSE_PRESSED && button == MouseEvent.BUTTON3)
        || isMacOsCtrlClick,
    isTertiaryPressed = (modifiersEx and MouseEvent.BUTTON2_DOWN_MASK) != 0
        || (id == MouseEvent.MOUSE_PRESSED && button == MouseEvent.BUTTON2),
    isBackPressed = (modifiersEx and MouseEvent.getMaskForButton(4)) != 0
        || (id == MouseEvent.MOUSE_PRESSED && button == 4),
    isForwardPressed = (modifiersEx and MouseEvent.getMaskForButton(5)) != 0
        || (id == MouseEvent.MOUSE_PRESSED && button == 5),
)

@OptIn(ExperimentalComposeUiApi::class)
private val MouseEvent.keyboardModifiers get() = PointerKeyboardModifiers(
    isCtrlPressed = (modifiersEx and InputEvent.CTRL_DOWN_MASK) != 0,
    isMetaPressed = (modifiersEx and InputEvent.META_DOWN_MASK) != 0,
    isAltPressed = (modifiersEx and InputEvent.ALT_DOWN_MASK) != 0,
    isShiftPressed = (modifiersEx and InputEvent.SHIFT_DOWN_MASK) != 0,
    isAltGraphPressed = (modifiersEx and InputEvent.ALT_GRAPH_DOWN_MASK) != 0,
    isSymPressed = false,
    isFunctionPressed = false,
    isCapsLockOn = getLockingKeyStateSafe(KeyEvent.VK_CAPS_LOCK),
    isScrollLockOn = getLockingKeyStateSafe(KeyEvent.VK_SCROLL_LOCK),
    isNumLockOn = getLockingKeyStateSafe(KeyEvent.VK_NUM_LOCK),
)

private fun getLockingKeyStateSafe(
    mask: Int
): Boolean = try {
    Toolkit.getDefaultToolkit().getLockingKeyState(mask)
} catch (_: Exception) {
    false
}

private val MouseEvent.isMacOsCtrlClick
    get() = (
            hostOs.isMacOS &&
                    ((modifiersEx and InputEvent.BUTTON1_DOWN_MASK) != 0) &&
                    ((modifiersEx and InputEvent.CTRL_DOWN_MASK) != 0)
            )
