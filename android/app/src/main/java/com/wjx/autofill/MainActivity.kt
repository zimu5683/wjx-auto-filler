package com.wjx.autofill

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.wjx.autofill.config.AnswerGroup
import com.wjx.autofill.config.TemplateStore
import com.wjx.autofill.databinding.ActivityMainBinding
import com.wjx.autofill.databinding.DialogQuestionPickerBinding
import com.wjx.autofill.qr.LinkCheck
import com.wjx.autofill.qr.QrDecoder
import com.wjx.autofill.qr.QrScanActivity
import com.wjx.autofill.qr.SurveyLinkValidator
import com.wjx.autofill.schedule.FileScheduledTaskStore
import com.wjx.autofill.schedule.Notifier
import com.wjx.autofill.schedule.PendingCaptchaStore
import com.wjx.autofill.schedule.SchedulePrefs
import com.wjx.autofill.schedule.ScheduleMath
import com.wjx.autofill.schedule.ScheduleTime
import com.wjx.autofill.schedule.ScheduledRunner
import com.wjx.autofill.schedule.ScheduledSubmitService
import com.wjx.autofill.schedule.ScheduledTask
import com.wjx.autofill.schedule.ScheduledTaskStore
import com.wjx.autofill.schedule.TaskState
import com.wjx.autofill.submit.BatchReport
import com.wjx.autofill.submit.CAPTCHA_TOKEN_TTL_MS
import com.wjx.autofill.submit.GroupOutcome
import com.wjx.autofill.submit.CaptchaHarvest
import com.wjx.autofill.submit.SubmitCoordinator
import com.wjx.autofill.submit.pendingCaptchaGroups
import com.wjx.autofill.ui.CaptchaActivity
import com.wjx.autofill.ui.EditorState
import com.wjx.autofill.ui.PairAdapter
import com.wjx.autofill.ui.QuestionAdapter
import com.wjx.autofill.ui.SubmitResultAdapter
import com.wjx.autofill.ui.SurveyOpenState
import com.wjx.autofill.ui.SurveyStatus
import com.wjx.autofill.update.AppUpdater
import com.wjx.autofill.update.UpdateChecker
import com.wjx.autofill.update.UpdateEvent
import com.wjx.autofill.update.UpdateInfo
import com.wjx.autofill.wjx.HttpWjxSurveyClient
import com.wjx.autofill.wjx.SubmitErrorCode
import com.wjx.autofill.wjx.SubmitResult
import com.wjx.autofill.wjx.SurveyModel
import com.wjx.autofill.wjx.WjxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面：问卷链接 → 左右双栏字段映射 → 多组内容 → 并行提交 → 结果反馈。
 *
 * 更新链路（update/）与扫码链路（qr/）都只是入口，失败一律不打断填表主流程。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: TemplateStore
    private lateinit var pairAdapter: PairAdapter
    private lateinit var questionAdapter: QuestionAdapter
    private lateinit var resultAdapter: SubmitResultAdapter

    private var state = EditorState()
    private var submitJob: Job? = null
    private var parseJob: Job? = null

    /** 最近一次提交用的模板（人机验证兜底重试要用同一份）。 */
    private var lastSubmittedTemplate: com.wjx.autofill.config.MappingTemplate? = null

    /** 并行提交编排器：保留实例是为了拿到各组 fetch 的会话 cookie（§13.3 方向①）。 */
    private val coordinator = SubmitCoordinator()

    /**
     * 已用掉人工验证兜底机会的分组：**每个需要兜底的组各有 1 次**（Lead 裁定 A），
     * 不设跨组上限。只有「验证环节实际发起过且用户未取消」才记入；结构性失败
     * （无 WebView / 页面无验证入口）用下面的开关整体禁用入口。
     */
    private val captchaRetriedGroups = mutableSetOf<Int>()

    /** 结构性失败后整体禁用人工验证入口（可观察行为 = 按钮对所有未兜底组立即隐藏）。 */
    private var captchaFallbackDisabled = false
    private var pendingCaptchaGroup = -1
    private var pendingCaptchaCookies: Map<String, String> = emptyMap()

    // ---- 定时自动提交（前台常驻服务；同一时刻只允许 1 个任务） ----
    private lateinit var scheduleStore: ScheduledTaskStore
    private lateinit var schedulePrefs: SchedulePrefs
    private var scheduleTask: ScheduledTask? = null
    private var suppressScheduleToggle = false
    private var scheduleFallbackJob: Job? = null

    /**
     * 已**自动进入过**验证页但没真正发起验证（用户返回/未唤起）的组：
     * 不消耗该组机会，但禁止自动再次进入，避免「返回→自动进入」死循环。
     */
    private val captchaAutoEnterBlocked = mutableSetOf<Int>()

    private val scanLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val text = result.data?.getStringExtra(QrScanActivity.EXTRA_SCAN_RESULT).orEmpty()
            if (text.isNotBlank()) acceptLinkText(text)
        }

    private val galleryLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) decodeGalleryImage(uri)
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) toast(R.string.schedule_need_notification_permission)
        }

    private val captchaLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val reason = result.data?.getStringExtra(CaptchaActivity.EXTRA_ERROR).orEmpty()
            val fatal = result.data?.getBooleanExtra(CaptchaActivity.EXTRA_FATAL, false) == true
            val started = result.data?.getBooleanExtra(CaptchaActivity.EXTRA_STARTED, false) == true
            val cancelled = result.data?.getBooleanExtra(CaptchaActivity.EXTRA_CANCELLED, false) == true
            val group = pendingCaptchaGroup
            pendingCaptchaGroup = -1
            if (result.resultCode != RESULT_OK) {
                // Lead 最终口径：只有「验证环节实际发起过」才消耗该组机会；
                // 用户取消 / 未唤起验证码 → 不消耗；结构性失败 → 直接终态（禁用入口）。
                if (fatal) {
                    captchaFallbackDisabled = true
                } else if (started && !cancelled && group >= 0) {
                    captchaRetriedGroups += group
                } else if (group >= 0) {
                    // 变更 1 防循环：没真正发起验证（取消/未唤起）→ 不消耗机会，
                    // 但禁止自动再次进入；「人工验证后重试」按钮仍保留为手动入口。
                    captchaAutoEnterBlocked += group
                }
                toast(reason.ifBlank { getString(R.string.captcha_cancelled) })
                state.resultVisible = true
                renderResults()
                return@registerForActivityResult
            }
            val harvest = CaptchaActivity.harvestFrom(result.data)
            if (harvest == null) {
                if (group >= 0) captchaRetriedGroups += group
                toast(R.string.captcha_no_result)
                state.resultVisible = true
                renderResults()
                return@registerForActivityResult
            }
            // §13.2 令牌时效：收割后 60 秒内必须完成「重抓 + 提交」。
            if (System.currentTimeMillis() - harvest.harvestedAtMillis > CAPTCHA_TOKEN_TTL_MS) {
                if (group >= 0) captchaRetriedGroups += group
                toast(R.string.captcha_expired)
                state.resultVisible = true
                renderResults()
                return@registerForActivityResult
            }
            pendingCaptchaGroup = group
            retryWithCaptcha(harvest)
        }

    // ------------------------------------------------------------------ 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        store = TemplateStore(filesDir)
        scheduleStore = FileScheduledTaskStore(filesDir)
        schedulePrefs = SchedulePrefs(this)
        scheduleTask = scheduleStore.load()
        state = EditorState.restoreFrom(savedInstanceState)

        binding.versionLabel.text = getString(R.string.label_version, BuildConfig.VERSION_NAME)
        setupLists()
        bootstrapTemplates(restored = savedInstanceState != null)
        setupActions()
        renderAll()

        if (savedInstanceState == null) {
            checkUpdateSilently()
            resumePendingCaptchaIfAny()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        state.updateGroupPairs(pairAdapter.currentPairs())
        state.saveTo(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        submitJob?.cancel()
        parseJob?.cancel()
    }

    // ------------------------------------------------------------------ 初始化

    private fun applyWindowInsets() {
        // Android 15（targetSdk 35）强制边到边，必须自己处理 system bar / IME inset。
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime(),
            )
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun setupLists() {
        // 映射列表改为动态填充的 LinearLayout（原 RecyclerView 在 ScrollView 内不增长，见 PairAdapter 注释）。
        pairAdapter = PairAdapter(binding.pairList) { pairs -> state.updateGroupPairs(pairs) }

        questionAdapter = QuestionAdapter { question -> fillField(question.topic.toString()) }

        resultAdapter = SubmitResultAdapter()
        binding.resultList.layoutManager = LinearLayoutManager(this)
        binding.resultList.adapter = resultAdapter
    }

    private fun bootstrapTemplates(restored: Boolean) {
        val outcome = store.load()
        state.templates = outcome.templates.toMutableList()
        val notices = (outcome.failures + outcome.warnings)
        if (notices.isNotEmpty()) state.templateStatus = notices.joinToString("；")
        if (!restored && state.templates.isNotEmpty()) {
            state.current = state.templates.first()
            state.groupIndex = 0
        }
        if (state.current.groups.isEmpty()) {
            state.current = state.current.copy(groups = listOf(AnswerGroup.blank("方案 1")))
        }
    }

    private fun setupActions() {
        binding.scanButton.setOnClickListener {
            scanLauncher.launch(QrScanActivity.intent(this))
        }
        binding.galleryButton.setOnClickListener {
            galleryLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
        binding.parseButton.setOnClickListener { parseSurvey() }
        binding.updateButton.setOnClickListener { checkUpdateManually() }

        binding.addGroupButton.setOnClickListener { addGroup() }
        binding.renameGroupButton.setOnClickListener { renameGroup() }
        binding.deleteGroupButton.setOnClickListener { deleteGroup() }

        binding.addRowButton.setOnClickListener {
            pairAdapter.addRow()
            renderPairList()
        }
        binding.deleteSelectedButton.setOnClickListener {
            if (pairAdapter.selectedCount() == 0) {
                toast(R.string.toast_no_selection)
            } else {
                pairAdapter.deleteSelected()
                renderPairList()
            }
        }
        binding.clearAllButton.setOnClickListener { confirmClearAll() }

        binding.saveTemplateButton.setOnClickListener { showSaveTemplateDialog() }
        binding.loadTemplateButton.setOnClickListener { showLoadTemplateDialog() }
        binding.deleteTemplateButton.setOnClickListener { showDeleteTemplateDialog() }

        binding.submitButton.setOnClickListener { submitAll() }
        binding.captchaFallbackButton.setOnClickListener { startCaptchaFallback() }
        binding.scheduleSwitch.setOnCheckedChangeListener { _, checked ->
            if (!suppressScheduleToggle) onScheduleToggled(checked)
        }
        // 响铃/震动：用户可选项（默认开）。偏好独立于任务存在，任务创建前后改都生效。
        binding.alertSoundSwitch.isChecked = schedulePrefs.alertSound
        binding.alertVibrateSwitch.isChecked = schedulePrefs.alertVibrate
        binding.alertSoundSwitch.setOnCheckedChangeListener { _, checked ->
            schedulePrefs.alertSound = checked
            renderSchedule()
        }
        binding.alertVibrateSwitch.setOnCheckedChangeListener { _, checked ->
            schedulePrefs.alertVibrate = checked
            renderSchedule()
        }
        binding.closeResultsButton.setOnClickListener {
            state.resultVisible = false
            renderResults()
        }
    }

    // ------------------------------------------------------------------ 渲染

    private fun renderAll() {
        binding.linkInput.setText(state.current.surveyUrl)
        binding.linkStatus.text = state.linkStatus
        binding.templateStatus.text = state.templateStatus
        renderGroups()
        pairAdapter.setPairs(state.group().pairs)
        renderPairList()
        renderResults()
        renderSurveyStatus()
        renderSchedule()
        renderSubmitButton()
    }

    // ------------------------------------------------------------------ 状态条 / 横幅 / 定时

    /** 当前问卷开放状态：优先页面解析值（T16 接线点），其次用户在定时任务里填的开放时间。 */
    private fun currentSurveyStatus(): SurveyStatus = SurveyStatus.fromModel(
        model = state.survey,
        scheduledOpenAtMillis = scheduleTask?.openAtMillis,
        now = System.currentTimeMillis(),
    )

    private fun renderSurveyStatus() {
        val status = currentSurveyStatus()
        binding.surveyStatusBar.text = when (status.state) {
            SurveyOpenState.UNKNOWN -> getString(R.string.survey_status_unknown)
            SurveyOpenState.NOT_OPEN -> getString(
                R.string.survey_status_not_open,
                ScheduleTime.format(status.openAtMillis ?: 0L),
            )
            SurveyOpenState.OPEN -> getString(R.string.survey_status_open)
        }
    }

    private fun renderSubmitButton() {
        val closed = currentSurveyStatus().state == SurveyOpenState.NOT_OPEN
        binding.submitButton.alpha = if (closed) 0.5f else 1f
        binding.submitButton.text = if (closed) {
            getString(R.string.submit_closed_label)
        } else {
            getString(R.string.action_submit)
        }
    }

    private fun renderSchedule() {
        val task = scheduleTask
        suppressScheduleToggle = true
        binding.scheduleSwitch.isChecked = task != null
        suppressScheduleToggle = false
        if (task != null) {
            binding.openAtInput.setText(ScheduleTime.format(task.openAtMillis))
            binding.leadMinutesInput.setText(task.leadMinutes.toString())
            binding.scheduleStatus.text = getString(
                R.string.schedule_status_line,
                scheduleStateLabel(task.state),
                ScheduleTime.format(task.openAtMillis),
            )
        } else {
            if (binding.leadMinutesInput.text.isNullOrBlank()) {
                binding.leadMinutesInput.setText(ScheduleMath.DEFAULT_LEAD_MINUTES.toString())
            }
            binding.scheduleStatus.text = getString(R.string.schedule_status_none)
        }
    }

    private fun scheduleStateLabel(state: TaskState): String = when (state) {
        TaskState.ARMED -> getString(R.string.schedule_state_armed)
        TaskState.REMINDED -> getString(R.string.schedule_state_reminded)
        TaskState.RUNNING -> getString(R.string.schedule_state_running)
        TaskState.DONE_OK -> getString(R.string.schedule_state_done_ok)
        TaskState.DONE_FAIL -> getString(R.string.schedule_state_done_fail)
        TaskState.CANCELLED -> getString(R.string.schedule_state_cancelled)
    }

    private fun renderLink() {
        binding.linkStatus.text = state.linkStatus
    }

    private fun renderPairList() {
        val count = pairAdapter.itemCount
        // 标题显示条数，让「没有上限」一眼可见（用户曾以为只能填 4 行）。
        binding.mappingTitle.text = getString(R.string.label_mapping_count, count)
        binding.emptyState.visibility = if (count > 0) View.GONE else View.VISIBLE
        binding.pairList.visibility = if (count > 0) View.VISIBLE else View.GONE
    }

    private fun renderGroups() {
        val names = state.groupNames()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.groupSpinner.adapter = adapter
        binding.groupSpinner.setSelection(
            state.groupIndex.coerceIn(0, (names.size - 1).coerceAtLeast(0)),
        )
        binding.groupSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == state.groupIndex) return
                state.updateGroupPairs(pairAdapter.currentPairs())
                state.groupIndex = position
                pairAdapter.setPairs(state.group().pairs)
                renderPairList()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun renderResults() {
        binding.resultPanel.visibility = if (state.resultVisible) View.VISIBLE else View.GONE
        binding.resultSummary.text = if (state.submitting) {
            state.progressText.ifBlank { state.summary }
        } else {
            state.summary
        }
        if (state.submitting) resultAdapter.clear() else resultAdapter.submit(state.outcomes)

        // 兜底按钮显示/隐藏**只看 errorCode + 每组尝试记录**，禁止文案匹配（§13.5）。
        // 判据是纯函数 pendingCaptchaGroups（submit/ 包），可被 JVM 单测直接覆盖。
        val pendingCaptcha = state.outcomes.pendingCaptchaGroups(captchaRetriedGroups)
        binding.captchaFallbackButton.visibility =
            if (pendingCaptcha.isNotEmpty() && !captchaFallbackDisabled && !state.submitting) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    // ------------------------------------------------------------------ 内容组

    private fun addGroup() {
        state.updateGroupPairs(pairAdapter.currentPairs())
        val groups = state.current.groups + AnswerGroup.blank("方案 ${state.current.groups.size + 1}")
        state.current = state.current.copy(groups = groups)
        state.groupIndex = groups.size - 1
        renderGroups()
        pairAdapter.setPairs(state.group().pairs)
        renderPairList()
    }

    private fun renameGroup() {
        val input = dialogInput(state.group().name)
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_group_name_title)
            .setView(input)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                val name = input.text.toString().trim().take(60)
                val groups = state.current.groups.toMutableList()
                val index = state.groupIndex.coerceIn(0, (groups.size - 1).coerceAtLeast(0))
                groups[index] = groups[index].copy(name = name)
                state.current = state.current.copy(groups = groups)
                renderGroups()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun deleteGroup() {
        if (state.current.groups.size <= 1) {
            toast(R.string.toast_need_one_group)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_delete_group)
            .setMessage(R.string.dialog_delete_group_message)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                val groups = state.current.groups.toMutableList()
                val index = state.groupIndex.coerceIn(0, groups.size - 1)
                groups.removeAt(index)
                state.current = state.current.copy(groups = groups)
                state.groupIndex = index.coerceIn(0, groups.size - 1)
                renderGroups()
                pairAdapter.setPairs(state.group().pairs)
                renderPairList()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_clear_all_title)
            .setMessage(R.string.dialog_clear_all_message)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                pairAdapter.clearAll()
                renderPairList()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ 链接 / 扫码 / 相册

    private fun acceptLinkText(raw: String) {
        when (val check = SurveyLinkValidator.checkSyntax(raw)) {
            is LinkCheck.Bad -> {
                state.linkStatus = getString(R.string.link_invalid, check.reason)
                renderLink()
                toast(state.linkStatus)
            }

            is LinkCheck.Ok -> applyLink(check.url, check.note)
        }
    }

    private fun applyLink(url: String, note: String) {
        state.current = state.current.copy(
            surveyUrl = url,
            shortId = SurveyLinkValidator.shortIdOf(url).orEmpty(),
        )
        binding.linkInput.setText(url)
        state.linkStatus = note.ifBlank { getString(R.string.link_check_running) }
        renderLink()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { SurveyLinkValidator.verifyReachable(url) }
            state.linkStatus = when (result) {
                is LinkCheck.Ok -> listOf(note, getString(R.string.link_ok))
                    .filter { it.isNotBlank() }
                    .joinToString("；")
                is LinkCheck.Bad -> getString(R.string.link_invalid, result.reason)
            }
            renderLink()
        }
    }

    private fun decodeGalleryImage(uri: Uri) {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    QrDecoder.decodeImageUri(this@MainActivity, uri)
                } catch (_: Throwable) {
                    null
                }
            }
            if (text.isNullOrBlank()) {
                toast(R.string.gallery_decode_failed)
            } else {
                acceptLinkText(text)
            }
        }
    }

    // ------------------------------------------------------------------ 解析问卷

    private fun parseSurvey() {
        val raw = binding.linkInput.text?.toString().orEmpty()
        when (val check = SurveyLinkValidator.checkSyntax(raw)) {
            is LinkCheck.Bad -> {
                state.linkStatus = getString(R.string.link_invalid, check.reason)
                renderLink()
                toast(state.linkStatus)
            }

            is LinkCheck.Ok -> {
                val url = check.url
                state.current = state.current.copy(
                    surveyUrl = url,
                    shortId = SurveyLinkValidator.shortIdOf(url).orEmpty(),
                )
                binding.linkInput.setText(url)
                state.linkStatus = getString(R.string.parse_running)
                renderLink()
                parseJob?.cancel()
                parseJob = lifecycleScope.launch {
                    // 引擎实现类没有默认参数（Kotlin override 不继承默认值），必须显式传 cookies。
                    val result = withContext(Dispatchers.IO) {
                        HttpWjxSurveyClient().fetch(url, emptyMap())
                    }
                    result.onSuccess { model ->
                        state.survey = model
                        // 用户要求：解析到开放时间就**强制覆盖**输入框；解析不到则保留原值并提示。
                        val parsedOpenAt = model.openAtMillis
                        binding.openAtInput.setText(
                            ScheduleTime.applyParsedOpenTime(
                                binding.openAtInput.text?.toString(),
                                parsedOpenAt,
                            ).orEmpty(),
                        )
                        state.linkStatus = if (parsedOpenAt == null) {
                            getString(R.string.parse_success, model.title, model.questions.size) +
                                "；" + getString(R.string.parse_no_open_time)
                        } else {
                            getString(R.string.parse_success, model.title, model.questions.size)
                        }
                        renderLink()
                        renderSurveyStatus()
                        showQuestionPicker(model)
                    }.onFailure { throwable ->
                        val human = (throwable as? WjxException)?.message
                            ?: throwable.message.orEmpty()
                        state.linkStatus = getString(R.string.parse_failed, human)
                        renderLink()
                    }
                }
            }
        }
    }

    private fun showQuestionPicker(model: SurveyModel) {
        val dialogBinding = DialogQuestionPickerBinding.inflate(layoutInflater)
        questionAdapter.submit(model.questions)
        dialogBinding.questionList.layoutManager = LinearLayoutManager(this)
        dialogBinding.questionList.adapter = questionAdapter
        dialogBinding.questionsEmpty.visibility =
            if (model.questions.isEmpty()) View.VISIBLE else View.GONE
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_questions_title_named, model.title))
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.action_close, null)
            .show()
    }

    /** 题目清单点选后把题号回填到左栏（先补第一个空行，没有空行就新增一行）。 */
    private fun fillField(identifier: String) {
        val blank = pairAdapter.firstBlankFieldIndex()
        if (blank >= 0) {
            pairAdapter.setFieldAt(blank, identifier)
        } else {
            pairAdapter.addRow(field = identifier)
        }
        renderPairList()
    }

    // ------------------------------------------------------------------ 模板

    private fun showSaveTemplateDialog() {
        state.updateGroupPairs(pairAdapter.currentPairs())
        val input = dialogInput(state.current.name, R.string.dialog_template_name_hint)
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_save_template_title)
            .setView(input)
            .setPositiveButton(R.string.action_confirm) { _, _ -> saveTemplate(input.text.toString()) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun saveTemplate(rawName: String) {
        val name = rawName.trim().take(60)
        if (name.isEmpty()) {
            toast(R.string.toast_template_name_required)
            return
        }
        val typedUrl = binding.linkInput.text?.toString()?.trim().orEmpty()
        // 同名覆盖（保留原 id）；**新名字必须换新 id** —— 否则会沿用 current.id，
        // 与库里同 id 的另一条冲突，删除时会误删两条（这正是「永远只有 1 个模板」的根因）。
        val existing = state.findTemplateByName(name)
        val template = state.current.copy(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
            name = name,
            surveyUrl = typedUrl.ifBlank { state.current.surveyUrl },
            shortId = SurveyLinkValidator.shortIdOf(typedUrl).orEmpty()
                .ifBlank { state.current.shortId },
            groups = state.current.groups
                .map { it.copy(pairs = it.effectivePairs()) }
                .ifEmpty { listOf(AnswerGroup.blank()) },
        ).normalized()
        state.current = template
        state.saveTemplateByName(template)
        persist()
        state.templateStatus = getString(R.string.toast_template_saved) +
            "（共 ${state.templates.size} 个模板）"
        renderAll()
    }

    private fun showLoadTemplateDialog() {
        if (state.templates.isEmpty()) {
            toast(R.string.toast_no_templates)
            return
        }
        val labels = state.templates.map { template ->
            "${template.name}（${template.shortId.ifBlank { "无 shortId" }}，${template.groups.size} 组）"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_load_template_title)
            .setItems(labels) { _, which ->
                val template = state.templates.getOrNull(which) ?: return@setItems
                state.current = template
                state.groupIndex = 0
                state.linkStatus = ""
                renderAll()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** 删除模板：先选模板，再二次确认。 */
    private fun showDeleteTemplateDialog() {
        if (state.templates.isEmpty()) {
            toast(R.string.toast_no_templates)
            return
        }
        val labels = state.templates.map { template ->
            "${template.name}（${template.groups.size} 组）"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.action_delete_template)
            .setItems(labels) { _, which ->
                val template = state.templates.getOrNull(which) ?: return@setItems
                AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_delete_template_title)
                    .setMessage(getString(R.string.dialog_delete_template_message, template.name))
                    .setPositiveButton(R.string.action_delete) { _, _ -> deleteTemplate(template) }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun deleteTemplate(template: com.wjx.autofill.config.MappingTemplate) {
        state.removeTemplate(template.id)
        if (state.current.id == template.id) {
            state.current = state.templates.firstOrNull()
                ?: com.wjx.autofill.config.MappingTemplate.blank()
            state.groupIndex = 0
        }
        persist()
        state.templateStatus = getString(R.string.toast_template_deleted, template.name) +
            "（共 ${state.templates.size} 个模板）"
        renderAll()
    }

    private fun persist() {
        if (!store.save(state.templates, appVersion = BuildConfig.VERSION_NAME)) {
            toast(R.string.toast_save_failed)
        }
    }

    // ------------------------------------------------------------------ 提交

    private fun submitAll() {
        if (state.submitting) return
        state.updateGroupPairs(pairAdapter.currentPairs())
        val template = state.current.copy(
            groups = state.current.groups.map { it.copy(pairs = it.effectivePairs()) },
        )
        if (template.surveyUrl.isBlank()) {
            toast(R.string.toast_need_link)
            return
        }
        if (!template.isUsable) {
            toast(R.string.toast_need_pairs)
            return
        }

        // 未开放时按钮置灰，但**手动路径仍可用**：确认后照样提交（服务端可能拒绝）。
        val status = currentSurveyStatus()
        if (status.state == SurveyOpenState.NOT_OPEN) {
            AlertDialog.Builder(this)
                .setTitle(R.string.submit_closed_confirm_title)
                .setMessage(
                    getString(
                        R.string.submit_closed_confirm_message,
                        ScheduleTime.format(status.openAtMillis ?: 0L),
                    ),
                )
                .setPositiveButton(R.string.submit_closed_confirm_ok) { _, _ -> doSubmit(template) }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
            return
        }
        doSubmit(template)
    }

    private fun doSubmit(template: com.wjx.autofill.config.MappingTemplate) {
        lastSubmittedTemplate = template
        captchaRetriedGroups.clear()
        captchaAutoEnterBlocked.clear()
        captchaFallbackDisabled = false
        pendingCaptchaGroup = -1
        state.submitting = true
        state.resultVisible = true
        state.outcomes = emptyList()
        state.summary = getString(R.string.submit_running, template.groups.size)
        state.progressText = state.summary
        renderResults()

        submitJob = lifecycleScope.launch {
            try {
                val report = coordinator.run(template) { done, total ->
                    state.progressText = getString(R.string.submit_progress, done, total)
                    renderResults()
                }
                state.outcomes = report.outcomes
                state.summary = report.summary()
                state.submitting = false
                renderResults()
                highlightUnmatched(report)
                // 变更 1：收到 E_CAPTCHA **直接进入验证界面**，不再要求先点按钮。
                autoEnterCaptchaIfNeeded()
            } catch (throwable: Throwable) {
                state.submitting = false
                state.summary = getString(R.string.submit_failed, throwable.message.orEmpty())
                renderResults()
            }
        }
    }

    /** 契约 §8.3：E_UNMATCHED 时把明细里的字段在左栏高亮，并切到含该字段的组。 */
    private fun highlightUnmatched(report: BatchReport) {
        val fields = mutableSetOf<String>()
        report.outcomes
            .filter { it.result.errorCode == SubmitErrorCode.UNMATCHED }
            .forEach { outcome ->
                QUOTED_PATTERN.findAll(outcome.result.message).forEach { match ->
                    fields += match.groupValues[1].trim()
                }
            }
        // 被跳过的未匹配字段（提交成功但字段没进问卷）同样要在左栏高亮 —— 不静默丢弃。
        report.outcomes.forEach { outcome ->
            outcome.result.skippedFields.forEach { fields += it.trim() }
        }
        if (fields.isEmpty()) return

        val targetIndex = state.current.groups.indexOfFirst { group ->
            group.pairs.any { fields.contains(it.field.trim()) }
        }
        if (targetIndex >= 0 && targetIndex != state.groupIndex) {
            state.groupIndex = targetIndex
            renderGroups()
            pairAdapter.setPairs(state.group().pairs)
        }
        pairAdapter.highlightUnmatched(fields)
        renderPairList()
    }

    // ------------------------------------------------------------------ 人机验证兜底

    /**
     * 契约 §13.1：**必须用户显式点击按钮**才启动 WebView，绝不自动打开。
     * 按钮的显示/隐藏由 renderResults() 按 `errorCode + 每组尝试记录` 决定。
     */
    private fun startCaptchaFallback() {
        val template = lastSubmittedTemplate ?: return
        if (captchaFallbackDisabled) return
        // 取第一个**还没用过机会**的组：多组都需要验证时，逐组各给一次入口。
        val index = state.outcomes.pendingCaptchaGroups(captchaRetriedGroups).firstOrNull()
        if (index == null) {
            // 状态不同步时的守卫：所有需要兜底的组都已用过机会（正常路径按钮已隐藏）。
            toast(R.string.captcha_exhausted)
            return
        }
        launchCaptchaFor(index)
    }

    /**
     * 变更 1（用户要求）：收到 E_CAPTCHA **直接进入验证界面**，不再要求用户先点按钮。
     *
     * 保留「人工验证后重试」按钮作为**重试入口**；已兜底组、以及「自动进入过但没真正发起
     * 验证（用户返回）」的组都不会再次自动进入 —— 避免「返回 → 自动进入」死循环。
     */
    private fun autoEnterCaptchaIfNeeded() {
        if (captchaFallbackDisabled) return
        val index = state.outcomes.pendingCaptchaGroups(captchaRetriedGroups)
            .firstOrNull { !captchaAutoEnterBlocked.contains(it) } ?: return
        launchCaptchaFor(index)
    }

    private fun launchCaptchaFor(index: Int) {
        val template = lastSubmittedTemplate ?: return
        pendingCaptchaGroup = index
        // §13.3 方向①：优先注入该组引擎会话 cookie；其次用落盘的现场；最后退回解析时的会话。
        pendingCaptchaCookies = coordinator.lastSessionCookies(index)
            .ifEmpty { pendingCaptchaCookies }
            .ifEmpty { state.survey?.cookies.orEmpty() }
        captchaLauncher.launch(
            CaptchaActivity.intent(this, template.surveyUrl, pendingCaptchaCookies),
        )
    }

    /**
     * 变更 2：定时到点被人机验证拦截后，通知（全屏 Intent）打开本界面时恢复现场，
     * 复用变更 1 的自动进入逻辑。
     */
    private fun resumePendingCaptchaIfAny() {
        val pendingStore = PendingCaptchaStore(filesDir)
        val pending = pendingStore.load() ?: return
        if (System.currentTimeMillis() - pending.createdAtMillis > PENDING_CAPTCHA_TTL_MS) {
            pendingStore.clear()
            return
        }
        val template = state.templates.firstOrNull { it.id == pending.templateId } ?: return
        lastSubmittedTemplate = template
        pendingCaptchaCookies = pending.cookies
        captchaRetriedGroups.clear()
        captchaAutoEnterBlocked.clear()
        captchaFallbackDisabled = false
        state.outcomes = listOf(
            GroupOutcome(
                index = pending.groupIndex,
                groupName = template.groups.getOrNull(pending.groupIndex)?.name.orEmpty(),
                result = SubmitResult(
                    ok = false,
                    httpStatus = 0,
                    message = getString(R.string.captcha_pending_message),
                    raw = null,
                    errorCode = SubmitErrorCode.CAPTCHA,
                ),
            ),
        )
        state.resultVisible = true
        renderResults()
        autoEnterCaptchaIfNeeded()
    }

    /** §13.2 第 10–12 步：同一会话重抓页面 → 带令牌提交 → 合并结果。 */
    private fun retryWithCaptcha(harvest: CaptchaHarvest) {
        val template = lastSubmittedTemplate
        val index = pendingCaptchaGroup
        if (template == null || index < 0) return
        // 该组的 1 次兜底机会已用掉（Lead 裁定：每组最多 1 次）。
        captchaRetriedGroups += index
        pendingCaptchaGroup = -1
        state.submitting = true
        state.summary = getString(R.string.captcha_retrying)
        renderResults()

        lifecycleScope.launch {
            val outcome = try {
                coordinator.retryGroupWithCaptcha(template, index, harvest)
            } catch (throwable: Throwable) {
                state.submitting = false
                state.summary = getString(R.string.submit_failed, throwable.message.orEmpty())
                renderResults()
                return@launch
            }
            state.submitting = false
            val updated = state.outcomes.toMutableList()
            val position = updated.indexOfFirst { it.index == index }
            if (position >= 0) updated[position] = outcome else updated += outcome
            state.outcomes = updated.sortedBy { it.index }
            state.summary = BatchReport(state.outcomes, 0L).summary()
            renderResults()
            when {
                outcome.result.ok -> toast(R.string.captcha_retry_ok)
                // 该组兜底机会已用尽（每组 1 次）；其他未兜底组的按钮仍会显示。
                outcome.result.errorCode == SubmitErrorCode.CAPTCHA -> toast(R.string.captcha_expired)
                else -> toast(getString(R.string.captcha_retry_failed, outcome.result.message))
            }
        }
    }

    // ------------------------------------------------------------------ 定时任务控制

    private fun onScheduleToggled(enabled: Boolean) {
        if (!enabled) {
            scheduleStore.clear()
            scheduleTask = null
            stopScheduleService()
            scheduleFallbackJob?.cancel()
            renderSchedule()
            renderSubmitButton()
            toast(R.string.schedule_cleared)
            return
        }
        val template = state.current
        if (template.name.isBlank()) {
            toast(R.string.schedule_need_template)
            revertScheduleSwitch()
            return
        }
        val openAt = ScheduleTime.parse(binding.openAtInput.text?.toString())
        if (openAt == null) {
            toast(R.string.schedule_need_open_time)
            revertScheduleSwitch()
            return
        }
        val task = ScheduledTask(
            templateId = template.id,
            templateName = template.name,
            openAtMillis = openAt,
            leadMinutes = ScheduleMath.normalizeLeadMinutes(
                binding.leadMinutesInput.text?.toString()?.trim()?.toIntOrNull()
                    ?: ScheduleMath.DEFAULT_LEAD_MINUTES,
            ),
            createdAtMillis = System.currentTimeMillis(),
            state = TaskState.ARMED,
        )
        scheduleStore.save(task)
        scheduleTask = task
        startScheduleService()
        renderSchedule()
        renderSubmitButton()
    }

    private fun revertScheduleSwitch() {
        suppressScheduleToggle = true
        binding.scheduleSwitch.isChecked = false
        suppressScheduleToggle = false
    }

    /** 启动前台服务；**失败不崩 App**：降级为普通后台协程 + 通知，并在 UI 显式反馈。 */
    private fun startScheduleService() {
        requestNotificationPermissionIfNeeded()
        val failure = try {
            ContextCompat.startForegroundService(this, ScheduledSubmitService.startIntent(this))
            null
        } catch (t: Throwable) {
            t
        }
        if (failure == null) {
            binding.scheduleStatus.text = getString(R.string.schedule_service_started)
        } else {
            val reason = failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName
            binding.scheduleStatus.text = getString(R.string.schedule_service_failed, reason)
            toast(binding.scheduleStatus.text.toString())
            startScheduleFallback()
        }
    }

    private fun stopScheduleService() {
        try {
            startService(ScheduledSubmitService.stopIntent(this))
        } catch (_: Throwable) {
            // 服务已停或不允许启动：忽略
        }
        Notifier.cancel(this, Notifier.ID_ONGOING)
    }

    /** 降级路径：普通后台协程（仅 App 前台时有效）+ 通知。 */
    private fun startScheduleFallback() {
        val task = scheduleTask ?: return
        scheduleFallbackJob?.cancel()
        scheduleFallbackJob = lifecycleScope.launch {
            val remindAt = ScheduleMath.remindAtMillis(task)
            if (remindAt > System.currentTimeMillis()) {
                delay(remindAt - System.currentTimeMillis())
                Notifier.notifyReminder(
                    this@MainActivity,
                    getString(R.string.schedule_remind_title),
                    getString(
                        R.string.schedule_remind_text,
                        task.templateName,
                        ScheduleTime.format(task.openAtMillis),
                    ),
                )
            }
            val wait = task.openAtMillis - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            ScheduledRunner.runOnce(this@MainActivity, task)
            scheduleTask = scheduleStore.load()
            renderSchedule()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Notifier.PERMISSION_POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Notifier.PERMISSION_POST_NOTIFICATIONS)
        }
    }

    // ------------------------------------------------------------------ 更新

    private fun checkUpdateSilently() {
        lifecycleScope.launch {
            val info = UpdateChecker.check(this@MainActivity, BuildConfig.VERSION_NAME)
            if (info != null) showUpdateDialog(info)
        }
    }

    private fun checkUpdateManually() {
        binding.updateButton.isEnabled = false
        lifecycleScope.launch {
            val info = UpdateChecker.check(this@MainActivity, BuildConfig.VERSION_NAME, force = true)
            binding.updateButton.isEnabled = true
            if (info == null) {
                toast(getString(R.string.update_latest, BuildConfig.VERSION_NAME))
            } else {
                showUpdateDialog(info)
            }
        }
    }

    private fun showUpdateDialog(info: UpdateInfo) {
        val notes = info.body.ifBlank { getString(R.string.update_release_notes_empty) }.take(800)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_found_title, info.version))
            .setMessage(notes)
            .setPositiveButton(R.string.update_download) { _, _ ->
                AppUpdater.downloadAndInstall(this, info) { event -> onUpdateEvent(event) }
            }
            .setNeutralButton(R.string.update_open_release) { _, _ -> openUrl(info.htmlUrl) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun onUpdateEvent(event: UpdateEvent) {
        when (event) {
            is UpdateEvent.Progress ->
                binding.versionLabel.text = getString(R.string.update_downloading, event.percent)
            is UpdateEvent.Ready ->
                binding.versionLabel.text = getString(R.string.label_version, BuildConfig.VERSION_NAME)
            is UpdateEvent.Failed -> {
                toast(event.reason)
                binding.versionLabel.text = getString(R.string.label_version, BuildConfig.VERSION_NAME)
            }
            is UpdateEvent.NeedInstallPermission -> toast(event.message)
        }
    }

    private fun openUrl(url: String) {
        if (url.isBlank()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Throwable) {
            toast(R.string.update_open_failed)
        }
    }

    // ------------------------------------------------------------------ 小工具

    private fun dialogInput(initial: String, hintRes: Int = R.string.dialog_group_name_hint): EditText {
        val padding = (16 * resources.displayMetrics.density).toInt()
        return EditText(this).apply {
            setText(initial)
            hint = getString(hintRes)
            setPadding(padding, padding / 2, padding, padding / 2)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun toast(resId: Int) {
        toast(getString(resId))
    }

    companion object {
        /** 通知（全屏 Intent）点进来时恢复「待人工验证」现场。 */
        const val EXTRA_RESUME_CAPTCHA = "extra_resume_captcha"

        /** 待验证现场的保鲜期：超过则丢弃，避免用户几天后打开 App 被跳转。 */
        private const val PENDING_CAPTCHA_TTL_MS = 30L * 60L * 1000L

        private val QUOTED_PATTERN = Regex("「([^」]{1,60})」")
    }
}
