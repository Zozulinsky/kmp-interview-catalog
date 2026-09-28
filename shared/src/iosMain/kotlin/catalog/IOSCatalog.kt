package catalog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** Handle возвращаемый из [IOSCatalog.observe]; вызов [close] останавливает подписку. */
class IOSCatalogObservation internal constructor(private val job: Job) {
    fun close() {
        job.cancel()
    }
}

/**
 * iOS-фасад над [CatalogStore]. Создаёт собственный Main-confined [CoroutineScope]
 * для одного экрана; сам [CoroutineScope] в Swift не попадает — конструктор,
 * принимающий scope, приватный, наружу торчат только фабрики.
 */
class IOSCatalog private constructor(
    repository: OffersRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val store = CatalogStore(scope, repository)

    fun selectAccount(accountId: String) {
        store.selectAccount(accountId)
    }

    fun refresh() {
        store.refresh()
    }

    /**
     * Подписывает [listener] на обновления состояния. Коллектор запущен в [scope],
     * который на Main, поэтому вызовы listener всегда приходят на главный поток —
     * никакого дополнительного withContext на Swift-стороне не требуется.
     * В этой первой версии на один фасад поддерживается только одна активная подписка.
     */
    fun observe(listener: (CatalogState) -> Unit): IOSCatalogObservation {
        val job = store.state
            .onEach { listener(it) }
            .launchIn(scope)
        return IOSCatalogObservation(job)
    }

    /** Закрывает store и собственный scope фасада (созданный им же в конструкторе). */
    fun close() {
        store.close()
        scope.cancel()
    }

    companion object {
        /** Фабрика для host-приложения с реальным репозиторием. */
        fun create(repository: OffersRepository): IOSCatalog = IOSCatalog(repository)

        /** Демо-инстанс с in-memory репозиторием — Swift не реализует suspend interface. */
        fun createDemo(): IOSCatalog = IOSCatalog(DemoOffersRepository())
    }
}

/** Фиксированные предложения после небольшой задержки — только для Swift-примера, не транспорт. */
private class DemoOffersRepository : OffersRepository {
    override suspend fun fetchOffers(accountId: String): List<Offer> {
        delay(400)
        return listOf(
            Offer(id = "demo-1", title = "Demo offer 1 for $accountId"),
            Offer(id = "demo-2", title = "Demo offer 2 for $accountId")
        )
    }
}
