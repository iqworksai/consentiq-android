package ai.iqworks.consentiq

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

public object ConsentIQ {
    private val mutableState = MutableStateFlow(ConsentIQState())
    public val state: StateFlow<ConsentIQState> = mutableState.asStateFlow()

    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    @Volatile
    private var engine: ConsentIQEngine? = null
    private var activeConfig: ConsentIQConfig? = null
    private var initJob: Job? = null
    private val tcfJobs = mutableListOf<Job>()

    @Volatile
    private var logger: ConsentIQLogger = AndroidLogger(debug = false)

    private var appContext: Context? = null
    private var activities: ForegroundActivityTracker? = null
    private val captchaProviders = HashMap<String, CaptchaTokenProvider>()

    public val isInitialized: Boolean
        get() = engine != null

    public val subjectId: String?
        get() = engine?.subjectId?.takeIf { it.isNotEmpty() }

    public val isTcfActive: Boolean
        get() = state.value.isTcfActive

    @Synchronized
    public fun init(
        context: Context,
        config: ConsentIQConfig,
    ) {
        if (engine != null && activeConfig == config) return
        val app = context.applicationContext ?: context
        trackActivities(app)
        val log = AndroidLogger(config.debug)
        val api = ConsentIQApiClient(config.propertyKey, config.apiUrl, logger = log)
        initWith(
            config = config,
            store = ConsentStore(SharedPreferencesStorage(context)),
            api = api,
            logger = log,
            captcha = captchaProviderFor(api.baseUrl),
            tcfHost = WebViewTcfHost(app, activities),
            tcfKeys = TcfKeyStore(app),
        )
    }

    @Synchronized
    internal fun initWith(
        config: ConsentIQConfig,
        store: ConsentStore,
        api: ConsentIQApiClient,
        logger: ConsentIQLogger,
        captcha: CaptchaTokenProvider = CaptchaTokenProvider.UNAVAILABLE,
        tcfHost: TcfHost = TcfHost.UNAVAILABLE,
        tcfKeys: TcfKeyWriter = TcfKeyWriter.NONE,
    ): Job {
        val running = initJob
        if (engine != null && activeConfig == config && running != null) return running
        running?.cancel()
        tcfJobs.forEach { it.cancel() }
        tcfJobs.clear()
        val next = ConsentIQEngine(config, store, api, logger, mutableState, captcha, tcfHost, tcfKeys)
        this.logger = logger
        activeConfig = config
        engine = next
        return scope.launch { next.initialize() }.also { initJob = it }
    }

    internal fun loadedConfigFor(
        propertyKey: String,
        baseUrl: String,
    ): PropertyConfig? = engine?.loadedConfigFor(propertyKey, baseUrl)

    @Synchronized
    internal fun captchaProviderFor(baseUrl: String): CaptchaTokenProvider {
        val context = appContext ?: return CaptchaTokenProvider.UNAVAILABLE
        return captchaProviders.getOrPut(baseUrl.trimEnd('/')) { WebViewCaptchaProvider(context, baseUrl, activities) }
    }

    private fun trackActivities(context: Context) {
        if (appContext != null) return
        appContext = context
        val application = context as? Application ?: return
        activities = ForegroundActivityTracker().also { application.registerActivityLifecycleCallbacks(it) }
    }

    public fun hasConsent(category: String): Boolean = state.value.hasConsent(category)

    public fun hasMarketingConsent(channel: String): Boolean = state.value.hasMarketingConsent(channel)

    public suspend fun acceptAll() {
        engine()?.acceptAll()
    }

    public fun acceptAllAsync(): Job = scope.launch { acceptAll() }

    public suspend fun rejectAll() {
        engine()?.rejectAll()
    }

    public fun rejectAllAsync(): Job = scope.launch { rejectAll() }

    public suspend fun savePreferences(consent: Map<String, Boolean>) {
        engine()?.savePreferences(consent)
    }

    public fun savePreferencesAsync(consent: Map<String, Boolean>): Job = scope.launch { savePreferences(consent) }

    public suspend fun updateConsent(updates: Map<String, Boolean>) {
        engine()?.updateConsent(updates)
    }

    public fun updateConsentAsync(updates: Map<String, Boolean>): Job = scope.launch { updateConsent(updates) }

    public suspend fun updateMarketingConsent(updates: Map<String, Boolean>) {
        engine()?.updateMarketingConsent(updates)
    }

    public fun updateMarketingConsentAsync(updates: Map<String, Boolean>): Job = scope.launch { updateMarketingConsent(updates) }

    public fun showSheet() {
        val current = engine() ?: return
        if (current.canOpenTcf) launchTcf(current) else current.showSheet()
    }

    public fun showTcfPreferences(): Boolean {
        val current = engine() ?: return false
        if (!current.canOpenTcf) return false
        launchTcf(current)
        return true
    }

    @Synchronized
    private fun launchTcf(current: ConsentIQEngine) {
        tcfJobs.removeAll { !it.isActive }
        tcfJobs += scope.launch { current.showTcf() }
    }

    public fun hideSheet() {
        engine()?.hideSheet()
    }

    public fun dismissSheet() {
        engine()?.dismissSheet()
    }

    public fun resetConsent() {
        engine()?.resetConsent()
    }

    private fun engine(): ConsentIQEngine? {
        val current = engine
        if (current == null) logger.warn("[ConsentIQ] Not initialized. Call ConsentIQ.init(context, config) first.")
        return current
    }
}
