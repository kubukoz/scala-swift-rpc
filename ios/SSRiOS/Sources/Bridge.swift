// JSON-RPC bridge + embedded (FFI) transport for the iOS host.
//
// This is the platform-agnostic half of the macOS `swift/main.swift`, pared
// down to the single transport iOS uses: the embedded Scala Native library.
// There is NO subprocess on iOS (the OS forbids spawning children), so unlike
// the macOS host there's no stdio path and no `#if SSR_FFI` — it's always FFI.
//
// The Scala app is linked into this binary as a static archive exporting
// `ssr_init` (+ `ScalaNativeInit`); messages flow over two shared-memory ring
// buffers. Bytes on the rings are LSP-framed JSON-RPC, identical to what the
// macOS host carries. The generated per-op API (onMount/onPatch/sendClick/…)
// lives in WireTypes.swift and extends `JSONRPCBridge` below.

import Foundation
import JSONRPC
import LanguageServerProtocol

// MARK: - JSON-RPC bridge

final class JSONRPCBridge {
    private let session: JSONRPCSession
    // Retains the transport (its background poller) for the bridge's lifetime.
    private let ffi: FfiTransport

    typealias NotificationHandler = (Data) -> Void
    typealias RequestHandler = (Data) async throws -> Data

    private var notificationHandlers: [String: NotificationHandler] = [:]
    private var requestHandlers: [String: RequestHandler] = [:]

    // Boot the embedded Scala app and wire the session over its rings.
    init() {
        let ffi = FfiTransport()
        self.ffi = ffi
        self.session = JSONRPCSession(channel: ffi.channel().withMessageFraming())
    }

    func on<P: Decodable>(_ method: String, _ handler: @escaping (P) -> Void) {
        notificationHandlers[method] = { data in
            do {
                let params = try JSONDecoder().decode(P.self, from: data)
                DispatchQueue.main.async { handler(params) }
            } catch {
                FileHandle.standardError.write("Decode error for \(method): \(error)\n".data(using: .utf8)!)
            }
        }
    }

    func on(_ method: String, _ handler: @escaping () -> Void) {
        notificationHandlers[method] = { _ in
            DispatchQueue.main.async { handler() }
        }
    }

    func registerRequest(_ method: String, _ handler: @escaping RequestHandler) {
        requestHandlers[method] = handler
    }

    func start() {
        Task { [weak self] in await self?.run() }
    }

    func sendNotification<P: Encodable & Sendable>(method: String, params: P) {
        let session = self.session
        Task {
            do {
                try await session.sendNotification(params, method: method)
            } catch {
                FileHandle.standardError.write("sendNotification \(method) failed: \(error)\n".data(using: .utf8)!)
            }
        }
    }

    func sendNotification(method: String) {
        let session = self.session
        Task {
            do {
                try await session.sendNotification(method: method)
            } catch {
                FileHandle.standardError.write("sendNotification \(method) failed: \(error)\n".data(using: .utf8)!)
            }
        }
    }

    // Drain incoming events and route by method name. Re-encode just the typed
    // `params` so handlers see the inner params object, not the JSON-RPC
    // envelope (matches the macOS host).
    private func run() async {
        let encoder = JSONEncoder()
        for await event in await session.eventSequence {
            switch event {
            case .notification(let note, _):
                guard let handler = notificationHandlers[note.method] else {
                    FileHandle.standardError.write("No notification handler for \(note.method)\n".data(using: .utf8)!)
                    continue
                }
                let paramsData = (try? encoder.encode(note.params)) ?? Data("{}".utf8)
                handler(paramsData)
            case .request(let req, let respond, _):
                let method = req.method
                guard let handler = requestHandlers[method] else {
                    let err = AnyJSONRPCResponseError(code: -32601, message: "Method not found: \(method)")
                    await respond(.failure(err))
                    continue
                }
                let paramsData = (try? encoder.encode(req.params)) ?? Data("{}".utf8)
                do {
                    let resultData = try await handler(paramsData)
                    await respond(.success(RawEncodable(data: resultData)))
                } catch {
                    let err = AnyJSONRPCResponseError(code: -32603, message: "Handler for \(method) threw: \(error)")
                    await respond(.failure(err))
                }
            case .error(let error):
                FileHandle.standardError.write("JSONRPC error: \(error)\n".data(using: .utf8)!)
            }
        }
    }
}

// Carries pre-encoded JSON through JSONRPCSession's Encodable result slot.
private struct RawEncodable: Encodable, @unchecked Sendable {
    let data: Data
    func encode(to encoder: Encoder) throws {
        let value = try JSONDecoder().decode(JSONRPC.JSONValue.self, from: data)
        try value.encode(to: encoder)
    }
}

// MARK: - Embedded (FFI) transport

// Scala Native's runtime bootstrap (GC, threads). Linked as a static archive,
// so there's no load-time constructor — call it once before any exported Scala
// function.
@_silgen_name("ScalaNativeInit")
func ScalaNativeInit() -> Int32

@_silgen_name("ssr_init")
func ssr_init() -> UnsafeMutableRawPointer

// A single-producer/single-consumer view over one shared ring buffer. Layout
// agreed with scala/lib-native/ffi.scala:
//   offset 0 : Int64 capacity   (power of two)
//   offset 8 : Int64 head       (read cursor, free-running)
//   offset 16: Int64 tail       (write cursor, free-running)
//   offset 24: UInt8 data[capacity]
private final class Ring {
    private let base: UnsafeMutableRawPointer
    private let capacity: Int
    private let mask: Int
    private let dataOffset = 24

    init(base: UnsafeMutableRawPointer) {
        self.base = base
        self.capacity = Int(base.load(fromByteOffset: 0, as: Int64.self))
        self.mask = self.capacity - 1
    }

    private var head: Int {
        get { Int(base.load(fromByteOffset: 8, as: Int64.self)) }
        set { base.storeBytes(of: Int64(newValue), toByteOffset: 8, as: Int64.self) }
    }
    private var tail: Int {
        get { Int(base.load(fromByteOffset: 16, as: Int64.self)) }
        set { base.storeBytes(of: Int64(newValue), toByteOffset: 16, as: Int64.self) }
    }

    private func byte(at index: Int) -> UnsafeMutableRawPointer {
        base.advanced(by: dataOffset + (index & mask))
    }

    func read() -> Data? {
        let h = head
        let t = tail
        let avail = t - h
        if avail <= 0 { return nil }
        var out = Data(count: avail)
        out.withUnsafeMutableBytes { (dst: UnsafeMutableRawBufferPointer) in
            for i in 0..<avail {
                dst[i] = byte(at: h + i).load(as: UInt8.self)
            }
        }
        head = h + avail  // publish consumption after copying
        return out
    }

    func write(_ data: Data) {
        data.withUnsafeBytes { (src: UnsafeRawBufferPointer) in
            var i = 0
            while i < src.count {
                let t = tail
                if t - head >= capacity {
                    continue  // full — let the consumer catch up
                }
                byte(at: t).storeBytes(of: src[i], as: UInt8.self)
                tail = t + 1  // publish this byte after writing it
                i += 1
            }
        }
    }
}

// Boots the embedded Scala app and exposes a DataChannel over its rings.
private final class FfiTransport {
    private let inRing: Ring
    private let outRing: Ring
    private let pollQueue = DispatchQueue(label: "ssr.ffi.poll")

    init() {
        let rc = ScalaNativeInit()
        if rc != 0 {
            FileHandle.standardError.write("ScalaNativeInit failed (\(rc))\n".data(using: .utf8)!)
        }
        let handle = ssr_init()
        let inPtr = handle.load(fromByteOffset: 0, as: UnsafeMutableRawPointer.self)
        let outPtr = handle.load(fromByteOffset: MemoryLayout<UnsafeMutableRawPointer>.size,
                                 as: UnsafeMutableRawPointer.self)
        self.inRing = Ring(base: inPtr)
        self.outRing = Ring(base: outPtr)
    }

    func channel() -> DataChannel {
        let inRing = self.inRing
        let outRing = self.outRing

        let write: DataChannel.WriteHandler = { data in
            inRing.write(data)  // only spins when full; safe inline
        }

        let (stream, continuation) = AsyncStream<Data>.makeStream()
        // Poll the out-ring; 1ms matches the Scala side's idle sleep.
        pollQueue.async {
            while true {
                if let data = outRing.read() {
                    continuation.yield(data)
                } else {
                    Thread.sleep(forTimeInterval: 0.001)
                }
            }
        }

        return DataChannel(writeHandler: write, dataSequence: stream)
    }
}
