// Every Swift shape the classifier is meant to recognise, written the way Swift is written:
// braces hanging at the end of the line, which is also what gives the move mechanic work to do.

import Foundation

protocol Fetching {
    func fetch(from url: URL) async throws -> Data
}

enum LoadState {
    case idle
    case loading
    case failed(reason: String)

    var isTerminal: Bool {
        switch self {
        case .failed:
            return true
        default:
            return false
        }
    }
}

struct Page<Item> {
    let items: [Item]
    let next: String?

    subscript(index: Int) -> Item {
        return items[index]
    }
}

extension URLSession: Fetching {
    func fetch(from url: URL) async throws -> Data {
        let (data, _) = try await self.data(from: url)
        return data
    }
}

actor RequestCounter {
    private var total = 0

    func bump() {
        total += 1
    }
}

final class Client {
    static let shared = Client(name: "default")

    private let session: Fetching
    private var observers: [(LoadState) -> Void] = []

    var name: String {
        didSet {
            notify()
        }
    }

    var summary: String {
        get {
            return "\(name): \(observers.count) observers"
        }
        set {
            name = newValue
        }
    }

    init(name: String) {
        self.name = name
        self.session = URLSession.shared
    }

    convenience init() {
        self.init(name: "anonymous")
    }

    deinit {
        observers.removeAll()
    }

    class func reset() {
        Client.shared.observers.removeAll()
    }

    @MainActor
    func load(from raw: String) {
        let pattern = #"^https?://[^\s"]+$"#
        let banner = """
            loading {
            please wait
            """
        print(banner, pattern)

        DispatchQueue.main.async {
            self.notify()
        }

        observers.forEach { observer in
            observer(.loading)
        }
    }

    private func notify() {
        for observer in observers {
            observer(.idle)
        }
    }

    struct Options {
        var retries: Int

        func validated() -> Options {
            return self
        }
    }
}
