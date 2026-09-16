import Foundation
import Network

enum HelloProbe {
    /// Connect to 127.0.0.1:port, read exactly one length-prefixed hello, close.
    /// Must run off the main and engine queues (uses a semaphore).
    static func probe(port: UInt16, timeout: TimeInterval = HelperConstants.probeTimeout) throws -> HelloMessage {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        let parameters = NWParameters(tls: nil, tcp: tcp)
        parameters.requiredInterfaceType = .loopback
        guard let nwPort = NWEndpoint.Port(rawValue: port) else {
            throw HelloFrameError.connectFailed("invalid port")
        }
        let connection = NWConnection(host: "127.0.0.1", port: nwPort, using: parameters)

        let lock = NSLock()
        var finished: Result<HelloMessage, Error>?
        let semaphore = DispatchSemaphore(value: 0)

        func finish(_ result: Result<HelloMessage, Error>) {
            lock.lock()
            defer { lock.unlock() }
            guard finished == nil else { return }
            finished = result
            semaphore.signal()
        }

        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                receiveHello(from: connection, finish: finish)
            case .failed(let error):
                finish(.failure(HelloFrameError.connectFailed(error.localizedDescription)))
            case .waiting(let error):
                finish(.failure(HelloFrameError.connectFailed(error.localizedDescription)))
            default:
                break
            }
        }
        connection.start(queue: DispatchQueue.global(qos: .userInitiated))

        let wait = semaphore.wait(timeout: .now() + timeout)
        connection.cancel()
        if wait == .timedOut {
            throw HelloFrameError.timeout
        }
        switch finished {
        case .success(let hello):
            return hello
        case .failure(let error):
            throw error
        case nil:
            throw HelloFrameError.timeout
        }
    }

    private static func receiveHello(
        from connection: NWConnection,
        finish: @escaping (Result<HelloMessage, Error>) -> Void
    ) {
        var buffer = Data()
        func pump() {
            connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { data, _, isComplete, error in
                if let error {
                    finish(.failure(HelloFrameError.connectFailed(error.localizedDescription)))
                    return
                }
                if let data, !data.isEmpty {
                    buffer.append(data)
                }
                do {
                    if let hello = try HelloFrame.consume(from: &buffer) {
                        finish(.success(hello))
                        return
                    }
                } catch {
                    finish(.failure(error))
                    return
                }
                if isComplete {
                    finish(.failure(HelloFrameError.truncated))
                    return
                }
                pump()
            }
        }
        pump()
    }
}
