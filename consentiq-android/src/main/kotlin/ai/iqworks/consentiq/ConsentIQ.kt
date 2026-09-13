package ai.iqworks.consentiq

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

    @Volatile
    private var logger: ConsentIQLogger = AndroidLogger(debug = false)

    public val isInitialized: Boolean
        get() = engine != null

    public val subjectId: String?
        get() = engine?.subjectId?.takeIf { it.isNotEmpty() }

    @Synchronized
    public fun init(
        context: Context,
        config: ConsentIQConfig,
    ) {
        if (engine != null && activeConfig == config) return
        val log = AndroidLogger(config.debug)
        initWith(
            config = config,
            store = ConsentStore(SharedPreferencesStorage(context)),
            api = ConsentIQApiClient(config.propertyKey, config.apiUrl, logger = log),
            logger = log,
        )
    }

    @Synchronized
    internal fun initWith(
        config: ConsentIQConfig,
        store: ConsentStore,
        api: ConsentIQApiClient,
        logger: ConsentIQLogger,
    ): Job {
        val running = initJob
        if (engine != null && activeConfig == config && running != null) return running
        running?.cancel()
        val next = ConsentIQEngine(config, store, api, logger, mutableState)
        this.logger = logger
        activeConfig = config
        engine = next
        return scope.launch { next.initialize() }.also { initJob = it }
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
        engine()?.showSheet()
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
