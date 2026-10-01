import Foundation
import UIKit
import Capacitor

@objc(MediaSaverPlugin)
public class MediaSaverPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "MediaSaverPlugin"
    public let jsName = "MediaSaver"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "saveStart", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "saveChunk", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "saveFinish", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "saveAbort", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "share", returnType: CAPPluginReturnPromise)
    ]

    private final class Session {
        let url: URL
        let handle: FileHandle

        init(url: URL, handle: FileHandle) {
            self.url = url
            self.handle = handle
        }
    }

    private var sessions: [String: Session] = [:]
    private var counter = 0
    private let lock = NSLock()

    deinit {
        lock.lock()
        let open = Array(sessions.values)
        sessions.removeAll()
        lock.unlock()
        open.forEach(discard)
    }

    @objc func saveStart(_ call: CAPPluginCall) {
        let filename = Self.sanitizeFilename(call.getString("filename"))
        let fm = FileManager.default
        do {
            let dir = try fm.url(for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            let url = Self.uniqueURL(in: dir, filename: filename)
            guard fm.createFile(atPath: url.path, contents: nil) else {
                call.reject("Could not open the file for writing", "save_failed")
                return
            }
            let handle = try FileHandle(forWritingTo: url)
            lock.lock()
            counter += 1
            let id = "ms\(counter)"
            sessions[id] = Session(url: url, handle: handle)
            lock.unlock()
            call.resolve(["id": id])
        } catch {
            call.reject("Could not open the file for writing", "save_failed", error)
        }
    }

    @objc func saveChunk(_ call: CAPPluginCall) {
        guard let id = call.getString("id"), let session = session(for: id) else {
            call.reject("Unknown save session", "no_session")
            return
        }
        guard let data = call.getString("data"), let bytes = Data(base64Encoded: data) else {
            call.reject("Chunk was not valid base64", "bad_chunk")
            return
        }
        do {
            try session.handle.write(contentsOf: bytes)
            call.resolve()
        } catch {
            removeSession(id)
            discard(session)
            call.reject("Could not write the chunk", "save_failed", error)
        }
    }

    @objc func saveFinish(_ call: CAPPluginCall) {
        guard let id = call.getString("id"), let session = removeSession(id) else {
            call.reject("Unknown save session", "no_session")
            return
        }
        do {
            try session.handle.close()
            call.resolve(["uri": session.url.absoluteString])
        } catch {
            discard(session)
            call.reject("Could not finish the file", "save_failed", error)
        }
    }

    @objc func saveAbort(_ call: CAPPluginCall) {
        if let id = call.getString("id"), let session = removeSession(id) {
            discard(session)
        }
        call.resolve()
    }

    @objc func share(_ call: CAPPluginCall) {
        guard let raw = call.getString("uri"), !raw.isEmpty, let url = URL(string: raw), url.isFileURL else {
            call.reject("Missing uri", "bad_uri")
            return
        }
        DispatchQueue.main.async {
            guard let host = self.bridge?.viewController else {
                call.reject("Could not open the share sheet", "share_failed")
                return
            }
            let sheet = UIActivityViewController(activityItems: [url], applicationActivities: nil)
            if let popover = sheet.popoverPresentationController {
                popover.sourceView = host.view
                popover.sourceRect = CGRect(x: host.view.bounds.midX, y: host.view.bounds.maxY - 1, width: 1, height: 1)
                popover.permittedArrowDirections = []
            }
            host.present(sheet, animated: true) {
                call.resolve()
            }
        }
    }

    private func session(for id: String) -> Session? {
        lock.lock()
        defer { lock.unlock() }
        return sessions[id]
    }

    @discardableResult
    private func removeSession(_ id: String) -> Session? {
        lock.lock()
        defer { lock.unlock() }
        return sessions.removeValue(forKey: id)
    }

    private func discard(_ session: Session) {
        try? session.handle.close()
        try? FileManager.default.removeItem(at: session.url)
    }

    private static func uniqueURL(in dir: URL, filename: String) -> URL {
        let fm = FileManager.default
        var candidate = dir.appendingPathComponent(filename)
        if !fm.fileExists(atPath: candidate.path) {
            return candidate
        }
        let ns = filename as NSString
        let ext = ns.pathExtension
        let base = ext.isEmpty ? filename : ns.deletingPathExtension
        let suffix = ext.isEmpty ? "" : "." + ext
        for i in 1..<10000 {
            candidate = dir.appendingPathComponent("\(base) (\(i))\(suffix)")
            if !fm.fileExists(atPath: candidate.path) {
                return candidate
            }
        }
        let stamp = Int(Date().timeIntervalSince1970 * 1000)
        return dir.appendingPathComponent("\(base) (\(stamp))\(suffix)")
    }

    private static func sanitizeFilename(_ raw: String?) -> String {
        var name = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        name = name.replacingOccurrences(of: "[/\\\\]", with: "_", options: .regularExpression)
        name = name.replacingOccurrences(of: "[\\x00-\\x1F\\x7F]", with: "", options: .regularExpression)
        if name == "." || name == ".." {
            name = ""
        }
        return name.isEmpty ? "download" : name
    }
}
