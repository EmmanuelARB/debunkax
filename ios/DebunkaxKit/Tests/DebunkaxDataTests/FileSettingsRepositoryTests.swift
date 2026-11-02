import DebunkaxCore
import XCTest

@testable import DebunkaxData

final class FileSettingsRepositoryTests: XCTestCase {
    private var url: URL!

    override func setUp() {
        url = FileManager.default.temporaryDirectory
            .appendingPathComponent("settings-\(UUID().uuidString)", isDirectory: true)
            .appendingPathComponent("settings.json")
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: url.deletingLastPathComponent())
    }

    private func current(_ repository: FileSettingsRepository) async -> Settings {
        var iterator = repository.settings.makeAsyncIterator()
        return await iterator.next()!
    }

    func testClearProviderForgetsProviderAndModelButKeepsTheCheckLimit() async throws {
        let repository = FileSettingsRepository(fileURL: url)
        try await repository.setProvider(.openRouter, modelId: "deepseek/deepseek-v4.1-flash")
        try await repository.setCheckLimit(50)

        try await repository.clearProvider()

        let settings = await current(repository)
        XCTAssertNil(settings.provider)
        XCTAssertNil(settings.modelId)
        XCTAssertEqual(settings.checkLimit, 50)
    }

    func testDefaultsAreEmptyAndUnlimited() async {
        let settings = await current(FileSettingsRepository(fileURL: url))
        XCTAssertEqual(settings, Settings(provider: nil, modelId: nil, checkLimit: nil))
    }

    func testSettingsPersistAcrossInstancesAndNilLimitMeansUnlimited() async throws {
        let repository = FileSettingsRepository(fileURL: url)
        try await repository.setProvider(.openRouter, modelId: "m")
        try await repository.setCheckLimit(20)

        let reopened = await current(FileSettingsRepository(fileURL: url))
        XCTAssertEqual(reopened, Settings(provider: .openRouter, modelId: "m", checkLimit: 20))

        try await repository.setCheckLimit(nil)
        let unlimited = await current(FileSettingsRepository(fileURL: url))
        XCTAssertNil(unlimited.checkLimit)
    }

    func testObserversSeeEachChange() async throws {
        let repository = FileSettingsRepository(fileURL: url)
        var iterator = repository.settings.makeAsyncIterator()
        let initial = await iterator.next()
        XCTAssertNil(initial?.provider)

        try await repository.setProvider(.openRouter, modelId: "m")
        let updated = await iterator.next()
        XCTAssertEqual(updated?.provider, .openRouter)
        XCTAssertEqual(updated?.modelId, "m")
    }
}
