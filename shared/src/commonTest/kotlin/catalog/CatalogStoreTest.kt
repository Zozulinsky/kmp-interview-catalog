package catalog

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ControlledRepository : OffersRepository {
    data class Request(val account: String, val response: CompletableDeferred<List<Offer>>)
    val requests = mutableListOf<Request>()
    override suspend fun fetchOffers(accountId: String): List<Offer> {
        val request = Request(accountId, CompletableDeferred())
        requests += request
        return request.response.await()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogStoreTest {
    @Test
    fun publishesSuccessfulResponse() = runTest {
        val repository = ControlledRepository()
        val store = CatalogStore(this, repository)
        store.selectAccount("A")
        runCurrent()
        assertTrue(store.state.value.loading)
        val offers = listOf(Offer("a", "Account A"))
        repository.requests.single().response.complete(offers)
        runCurrent()
        assertEquals(offers, store.state.value.offers)
        assertFalse(store.state.value.loading)
        store.close()
    }

    @Test
    fun failedRefreshKeepsAccountOffers() = runTest {
        val repository = ControlledRepository()
        val store = CatalogStore(this, repository)
        store.selectAccount("A")
        runCurrent()
        val offers = listOf(Offer("a", "Account A"))
        repository.requests[0].response.complete(offers)
        runCurrent()
        store.refresh()
        runCurrent()
        repository.requests[1].response.completeExceptionally(IllegalStateException("offline"))
        runCurrent()
        assertEquals(offers, store.state.value.offers)
        assertEquals("offline", store.state.value.error)
        store.close()
    }

    @Test
    fun closeDoesNotCancelHostScope() = runTest {
        val parent = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + parent)
        val store = CatalogStore(scope, ControlledRepository())
        store.selectAccount("A")
        runCurrent()
        store.close()
        runCurrent()
        assertTrue(parent.isActive)
        scope.cancel()
    }
}
