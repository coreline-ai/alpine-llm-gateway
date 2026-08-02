package dev.alpine.runtime.sample

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import dev.alpine.runtime.api.RuntimeCommandRequest
import dev.alpine.runtime.api.RuntimeLifecycleState
import dev.alpine.runtime.api.RuntimeTerminalSignal
import dev.alpine.runtime.host.RuntimeHostOperation
import dev.alpine.runtime.host.RuntimeHostState
import dev.alpine.runtime.host.RuntimeHostStateListener
import dev.alpine.runtime.api.RuntimeSubscription

class RuntimeSampleActivity : Activity() {
    private val controller by lazy { (application as RuntimeSampleApplication).runtimeController }
    private var subscription: RuntimeSubscription? = null
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var output: TextView
    private lateinit var terminalOutput: TextView
    private lateinit var terminalInput: EditText
    private lateinit var install: Button
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var health: Button
    private lateinit var repair: Button
    private lateinit var reset: Button
    private lateinit var execute: Button
    private lateinit var terminal: Button
    private lateinit var send: Button
    private var lastAnnouncedLifecycle: RuntimeLifecycleState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_runtime_sample)
        bindViews()
        install.setOnClickListener { controller.install() }
        start.setOnClickListener { controller.start() }
        stop.setOnClickListener { controller.stop() }
        health.setOnClickListener { controller.refreshHealth() }
        repair.setOnClickListener { controller.repair() }
        reset.setOnClickListener { controller.reset() }
        execute.setOnClickListener {
            controller.execute(
                RuntimeCommandRequest(
                    executable = "/bin/sh",
                    arguments = listOf("-lc", "printf 'Alpine '; cat /etc/alpine-release; uname -a"),
                ),
            )
        }
        terminal.setOnClickListener {
            if (controller.currentState().terminalActive) controller.closeTerminal() else controller.openTerminal()
        }
        send.setOnClickListener { submitTerminal() }
        terminalInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitTerminal()
                true
            } else {
                false
            }
        }
        terminal.setOnLongClickListener {
            controller.signalTerminal(RuntimeTerminalSignal.INTERRUPT)
            true
        }
    }

    override fun onStart() {
        super.onStart()
        subscription = controller.addStateListener(RuntimeHostStateListener { next ->
            runOnUiThread { render(next) }
        })
        controller.refreshHealth()
    }

    override fun onStop() {
        subscription?.close()
        subscription = null
        super.onStop()
    }

    private fun bindViews() {
        status = findViewById(R.id.runtime_status)
        progress = findViewById(R.id.runtime_progress)
        output = findViewById(R.id.command_output)
        terminalOutput = findViewById(R.id.terminal_output)
        terminalInput = findViewById(R.id.terminal_input)
        install = findViewById(R.id.install_button)
        start = findViewById(R.id.start_button)
        stop = findViewById(R.id.stop_button)
        health = findViewById(R.id.health_button)
        repair = findViewById(R.id.repair_button)
        reset = findViewById(R.id.reset_button)
        execute = findViewById(R.id.execute_button)
        terminal = findViewById(R.id.terminal_button)
        send = findViewById(R.id.send_button)
    }

    private fun submitTerminal() {
        val value = terminalInput.text.toString()
        if (value.isNotBlank()) {
            controller.sendTerminalInput(value)
            terminalInput.text.clear()
        }
    }

    private fun render(state: RuntimeHostState) {
        val idle = state.operation == RuntimeHostOperation.IDLE
        status.text = buildString {
            append(state.runtimeState.lifecycle.koreanLabel())
            state.runtimeState.activeVersion?.let { append(" · ").append(it) }
            state.health?.takeIf {
                state.runtimeState.lifecycle != RuntimeLifecycleState.NOT_INSTALLED
            }?.let { append(if (it.healthy) " · 정상" else " · 복구 필요") }
            state.lastErrorCode?.let { append("\n오류 코드: ").append(it.name) }
        }
        progress.visibility = if (state.runtimeState.progressPercent != null) View.VISIBLE else View.GONE
        progress.progress = state.runtimeState.progressPercent ?: 0
        install.isEnabled = idle && state.runtimeState.lifecycle == RuntimeLifecycleState.NOT_INSTALLED
        start.isEnabled = idle && state.runtimeState.lifecycle == RuntimeLifecycleState.READY
        stop.isEnabled = idle && state.runtimeState.lifecycle == RuntimeLifecycleState.RUNNING
        health.isEnabled = idle && state.runtimeState.lifecycle != RuntimeLifecycleState.NOT_INSTALLED
        repair.isEnabled = idle && state.runtimeState.lifecycle in setOf(
            RuntimeLifecycleState.REPAIR_REQUIRED,
            RuntimeLifecycleState.FAILED,
        )
        reset.isEnabled = idle && state.runtimeState.lifecycle !in setOf(
            RuntimeLifecycleState.INSTALLING,
            RuntimeLifecycleState.STARTING,
            RuntimeLifecycleState.STOPPING,
        )
        execute.isEnabled = idle && state.sessionActive
        terminal.isEnabled = idle && state.sessionActive
        terminal.text = if (state.terminalActive) "터미널 닫기 (길게 눌러 Ctrl+C)" else "터미널 열기"
        terminalInput.isEnabled = state.terminalActive
        send.isEnabled = state.terminalActive
        output.text = state.commandOutput.ifEmpty { "명령 결과" }
        terminalOutput.text = state.terminalText.ifEmpty { "터미널 출력" }
        if (lastAnnouncedLifecycle != state.runtimeState.lifecycle) {
            status.announceForAccessibility(status.text)
            lastAnnouncedLifecycle = state.runtimeState.lifecycle
        }
    }

    private fun RuntimeLifecycleState.koreanLabel(): String = when (this) {
        RuntimeLifecycleState.NOT_INSTALLED -> "설치 필요"
        RuntimeLifecycleState.INSTALLING -> "설치 중"
        RuntimeLifecycleState.READY -> "실행 준비"
        RuntimeLifecycleState.STARTING -> "시작 중"
        RuntimeLifecycleState.RUNNING -> "실행 중"
        RuntimeLifecycleState.STOPPING -> "종료 중"
        RuntimeLifecycleState.REPAIR_REQUIRED -> "복구 필요"
        RuntimeLifecycleState.FAILED -> "확인 필요"
    }
}
