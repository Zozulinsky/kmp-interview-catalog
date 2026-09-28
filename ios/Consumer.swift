import Foundation
import CatalogKit

/// Владелец экрана: держит один IOSCatalog и одну подписку на его состояние.
@MainActor
final class CatalogViewModel {
    private var catalog: IOSCatalog?
    private var observation: IOSCatalogObservation?

    private(set) var isLoading = false
    private(set) var offers: [Offer] = []
    private(set) var errorMessage: String?

    func start() {
        let instance = IOSCatalog.companion.createDemo()
        catalog = instance
        // Замыкание уже приходит на главный поток — scope фасада Main-confined.
        observation = instance.observe { [weak self] state in
            guard let self else { return }
            self.isLoading = state.loading
            self.offers = state.offers
            self.errorMessage = state.error
            print("account=\(state.accountId ?? "-") loading=\(state.loading) offers=\(self.offers.count) error=\(state.error ?? "-")")
        }
        instance.selectAccount(accountId: "acc-1")
    }

    func refresh() {
        catalog?.refresh()
    }

    func selectAccount(_ accountId: String) {
        catalog?.selectAccount(accountId: accountId)
    }

    func close() {
        observation?.close()
        observation = nil
        catalog?.close()
        catalog = nil
    }
}
