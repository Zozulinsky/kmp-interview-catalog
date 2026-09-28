package catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Одно предложение в каталоге. Простой immutable DTO без бизнес-логики.
 */
data class Offer(
    val id: String,
    val title: String
)

/**
 * Состояние экрана каталога, единым снимком через один StateFlow.
 */
data class CatalogState(
    val loading: Boolean = false,
    val offers: List<Offer> = emptyList(),
    val accountId: String? = null,
    val error: String? = null
)

/**
 * Источник предложений. Конкретный транспорт (сеть, БД и т.п.) вне этого PR;
 * интерфейс существует, чтобы commonTest мог подставлять управляемые фейки.
 */
interface OffersRepository {
    suspend fun fetchOffers(accountId: String): List<Offer>
}

/**
 * Хранилище состояния каталога для одного host-приложения/экрана.
 *
 * [scope] передаёт вызывающая сторона и владеет его жизненным циклом; [CatalogStore]
 * только запускает в нём свои задачи и не отменяет сам scope в [close].
 * Все публичные методы должны вызываться с одного диспетчера (на iOS — Main);
 * [repository] может уходить в подвешенное состояние на любом своём диспетчере.
 */
class CatalogStore(
    private val scope: CoroutineScope,
    private val repository: OffersRepository
) {
    private val _state = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = _state.asStateFlow()

    // Последние удачные предложения по каждому аккаунту — чтобы показать их сразу без ожидания сети.
    private val cache = mutableMapOf<String, List<Offer>>()

    private var refreshJob: Job? = null
    private var closed = false

    fun selectAccount(accountId: String) {
        if (closed) return
        refreshJob?.cancel()
        val cached = cache[accountId].orEmpty()
        _state.value = CatalogState(
            loading = true,
            offers = cached,
            accountId = accountId,
            error = null
        )
        startRefresh(accountId)
    }

    fun refresh() {
        if (closed) return
        val accountId = _state.value.accountId ?: return
        refreshJob?.cancel()
        _state.value = _state.value.copy(loading = true, error = null)
        startRefresh(accountId)
    }

    /** Останавливает собственные задачи store. Переданный [scope] host закрывает сам. */
    fun close() {
        closed = true
        refreshJob?.cancel()
    }

    private fun startRefresh(accountId: String) {
        // Новый job уже отменил предыдущий в selectAccount/refresh, так что в state попадёт только последний ответ.
        refreshJob = scope.launch {
            try {
                val offers = repository.fetchOffers(accountId)
                cache[accountId] = offers
                if (_state.value.accountId == accountId) {
                    _state.value = CatalogState(
                        loading = false,
                        offers = offers,
                        accountId = accountId,
                        error = null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Ошибка обновления сохраняет то, что уже было показано (кэш или прежний offers).
                if (_state.value.accountId == accountId) {
                    _state.value = _state.value.copy(
                        loading = false,
                        error = t.message ?: "Unknown error"
                    )
                }
            }
        }
    }
}
