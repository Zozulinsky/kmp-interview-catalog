# CatalogKit — KMP SDK персонального каталога

Kotlin Multiplatform SDK, который загружает персональные предложения выбранного аккаунта и отдаёт экрану единый снимок состояния: признак загрузки, список предложений, аккаунт и ошибку. Бизнес-логика общая для Android и iOS; транспорт (сеть, база) подключает приложение через интерфейс `OffersRepository`. Для iOS модуль собирается в framework `CatalogKit`, Swift-код работает с ним через фасад `IOSCatalog`.

## Состав

```text
shared/                                              KMP-модуль
  src/commonMain/kotlin/catalog/CatalogStore.kt      Offer, CatalogState, OffersRepository, CatalogStore
  src/iosMain/kotlin/catalog/IOSCatalog.kt           IOSCatalog, IOSCatalogObservation — фасад для Swift
  src/commonTest/kotlin/catalog/CatalogStoreTest.kt  ControlledRepository и тесты Store
ios/Consumer.swift                                   Пример потребителя framework: владелец экрана на Swift
```

Требования: Kotlin 2.4.0, Gradle 9.5.0, kotlinx.coroutines 1.10.2, JDK 21; для iOS — Xcode с iOS SDK.

## Использование

### Android и JVM

`CatalogStore` создаёт приложение и передаёт ему свой `CoroutineScope` (на Android — `viewModelScope`) и реализацию `OffersRepository`:

```kotlin
val store = CatalogStore(viewModelScope, repository)
viewModelScope.launch { store.state.collect { render(it) } }
store.selectAccount("acc-1")   // показывает кэш аккаунта и запускает обновление
store.refresh()                // повторное обновление текущего аккаунта
store.close()                  // при окончательном закрытии экрана; scope остаётся у приложения
```

### iOS

Swift получает готовый framework и работает через `IOSCatalog`: фасад сам создаёт `CatalogStore` и Main-scope для него. Реализацию `OffersRepository` передают в `IOSCatalog.create(repository)`; `createDemo()` даёт экземпляр со встроенными демо-данными.

```swift
let catalog = IOSCatalog.companion.createDemo()
let observation = catalog.observe { state in
    // вызывается на главном потоке
}
catalog.selectAccount(accountId: "acc-1")
catalog.refresh()
// при окончательном закрытии экрана
observation.close()
catalog.close()
```

Полный пример — [ios/Consumer.swift](ios/Consumer.swift).

## Контракт

- **Аккаунт и кэш.** Выбранный аккаунт определяет видимое состояние и ключ кэша. При ошибке обновления остаются доступные предложения этого аккаунта. Актуален последний начатый refresh.
- **Отмена.** Отмена — прекращение работы, а не доменная ошибка. Результат транспорта может прийти после запроса отмены. Правила актуальности результата и окончательного закрытия Store должны выполняться для каждой реализации repository.
- **Данные.** List в результате repository доступен только для чтения; владение памятью не передаётся, источник вправе переиспользовать свои данные. Уже выданные SDK снимки состояния и данные кэша сохраняют своё значение независимо от дальнейшей работы repository. Offer — неизменяемый DTO.
- **Потоки и закрытие.** Команды Store и его продолжения выполняются на одном однопоточном dispatcher (Main на iOS, Main или Main.immediate на Android). Закрытие Store окончательное: последующие ответы не меняют его состояние/кэш. Переданный CoroutineScope принадлежит host; Store закрывает только свои работы.
- **iOS-фасад.** IOSCatalog владеет собственным Main scope и Store. Observation handle управляет наблюдением, IOSCatalog.close — всем фасадом. В этой версии у одного фасада один наблюдатель, независимые фасады возможны. Swift-владелец обязан закрыть handle и facade при окончательном закрытии экрана.
- **Сессия.** Одна неизменная авторизованная сессия. Logout закрывает экземпляры и уничтожает их кэши.
- **Вне этой версии.** TTL, дисковый кэш, пагинация и сетевой слой.

## Тесты

В `commonTest` есть `ControlledRepository` — управляемый репозиторий для тестов — и три теста `CatalogStoreTest`: успешное обновление, ошибка обновления с сохранением предложений аккаунта, закрытие Store без отмены scope приложения. Их можно расширять или добавить свой фейк. Тесты общие и выполняются на JVM и на iOS Simulator.

## Сборка и проверка

Команды из корня проекта, нужны JDK 21 и Xcode:

```sh
./gradlew :shared:jvmTest
./gradlew :shared:jvmTest --tests catalog.CatalogStoreTest.publishesSuccessfulResponse
./gradlew :shared:iosSimulatorArm64Test
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
```

`jvmTest` и `iosSimulatorArm64Test` выполняют commonTest на JVM и на iOS Simulator. Framework появляется в `shared/build/bin/iosSimulatorArm64/debugFramework/CatalogKit.framework`.

После изменений Kotlin сначала пересоберите framework из текущих исходников, затем проверьте Swift-потребителя против него:

```sh
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
swiftc -swift-version 6 -module-cache-path /tmp/catalog-kmp-swift-cache -typecheck \
  -target arm64-apple-ios16.0-simulator \
  -sdk "$(xcrun --sdk iphonesimulator --show-sdk-path)" \
  -F shared/build/bin/iosSimulatorArm64/debugFramework ios/Consumer.swift
```

`typecheck` проверяет типы и импорт именно собранного framework; он не исполняет вызовы Swift → Kotlin на iOS.
