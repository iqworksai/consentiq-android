package ai.iqworks.consentiq

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

internal class TcfSession(
    private val host: TcfHost,
    private val keys: TcfKeyWriter,
    private val store: ConsentStore,
    private val pageUrl: String,
    private val readyTimeoutMs: Long = TCF_READY_TIMEOUT_MS,
    private val answerTimeoutMs: Long = TCF_ANSWER_TIMEOUT_MS,
    private val closeGraceMs: Long = TCF_CLOSE_GRACE_MS,
    private val newToken: () -> String = ::newTcfToken,
) {
    suspend fun run(
        subjectId: String,
        reopen: Boolean,
        onShow: () -> Unit,
    ): TcfOutcome {
        val events = Channel<TcfEvent>(Channel.UNLIMITED)
        val page =
            try {
                host.open(pageUrl, reopen) { events.trySend(it) }
            } catch (e: TcfException) {
                return TcfOutcome.Failed(e.code, e.message)
            } catch (e: RuntimeException) {
                return TcfOutcome.Failed(TCF_UNAVAILABLE, e.message ?: "web view open failed")
            }
        return try {
            drive(page, events, subjectId, reopen, onShow)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TcfOutcome.Failed(TCF_UNAVAILABLE, e.message)
        } finally {
            runCatching { page.release() }
        }
    }

    private suspend fun drive(
        page: TcfPage,
        events: Channel<TcfEvent>,
        subjectId: String,
        reopen: Boolean,
        onShow: () -> Unit,
    ): TcfOutcome {
        val ready =
            withTimeoutOrNull(readyTimeoutMs) {
                var event = events.receive()
                while (event !is TcfEvent.Failed && (event as? TcfEvent.Page)?.message != TcfMessage.Ready) event = events.receive()
                event
            } ?: return TcfOutcome.Failed(TCF_TIMEOUT, "page not ready")
        if (ready is TcfEvent.Failed) return TcfOutcome.Failed(TCF_LOAD_FAILED, ready.reason)

        val token = newToken()
        page.start(tcfStartScript(subjectId, store.loadTcf(), store.loadConsent(), reopen, token))

        val answer =
            withTimeoutOrNull(answerTimeoutMs) { awaitStep(events, token, acceptShow = true, acceptBack = false) }
                ?: return TcfOutcome.Failed(TCF_TIMEOUT, "page did not answer")
        if (answer is Step.Done) return answer.outcome

        page.present()
        onShow()
        val choice = awaitStep(events, token, acceptShow = false, acceptBack = reopen)
        if (choice is Step.Done) return choice.outcome

        page.dismiss()
        if (!(choice as Step.Back).interacted) return TcfOutcome.Closed
        val late = withTimeoutOrNull(closeGraceMs) { awaitStep(events, token, acceptShow = false, acceptBack = false) }
        return (late as? Step.Done)?.outcome ?: TcfOutcome.Closed
    }

    private suspend fun awaitStep(
        events: Channel<TcfEvent>,
        token: String,
        acceptShow: Boolean,
        acceptBack: Boolean,
    ): Step {
        while (true) {
            when (val step = stepFor(events.receive(), token)) {
                Step.Wait -> Unit
                Step.Shown -> if (acceptShow) return step
                is Step.Back -> if (acceptBack) return step
                is Step.Done -> return step
            }
        }
    }

    private fun stepFor(
        event: TcfEvent,
        token: String,
    ): Step =
        when (event) {
            is TcfEvent.Failed -> Step.Done(TcfOutcome.Failed(TCF_LOAD_FAILED, event.reason))
            is TcfEvent.Back -> Step.Back(event.interacted)
            is TcfEvent.Page -> if (event.token == token) stepFor(event.message) else Step.Wait
        }

    private fun stepFor(message: TcfMessage): Step =
        when (message) {
            TcfMessage.Ready -> Step.Wait
            TcfMessage.Show -> Step.Shown
            TcfMessage.Current -> {
                keys.writeGdprApplies(1)
                Step.Done(TcfOutcome.Current)
            }
            TcfMessage.NotApplicable -> {
                keys.writeGdprApplies(0)
                Step.Done(TcfOutcome.NotApplicable)
            }
            TcfMessage.Closed -> Step.Done(TcfOutcome.Closed)
            is TcfMessage.Decision -> {
                keys.write(message.keys)
                store.saveTcf(message.stored)
                Step.Done(TcfOutcome.Decided(message.consent, message.scope))
            }
            is TcfMessage.Error ->
                Step.Done(if (message.code == TCF_OFF) TcfOutcome.Off else TcfOutcome.Failed(TCF_PAGE_ERROR, message.code))
        }

    private sealed interface Step {
        data object Wait : Step

        data object Shown : Step

        data class Back(
            val interacted: Boolean,
        ) : Step

        data class Done(
            val outcome: TcfOutcome,
        ) : Step
    }
}
