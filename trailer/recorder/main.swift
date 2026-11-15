// Records one app's window plus that app's own audio (system audio, not the microphone)
// using ScreenCaptureKit, and writes an H.264/AAC .mp4.
//
// Usage: recorder --signal-dir DIR --out FILE [--width 1920] [--height 1080] [--fps 60]
//
// Waits for DIR/start (containing the target process ID), records that process's largest
// window, and stops when DIR/stop appears, the process exits, or on Ctrl-C.

import AppKit
import AVFoundation
import CoreMedia
import Foundation
import ScreenCaptureKit

func arg(_ name: String, _ fallback: String? = nil) -> String {
    let args = CommandLine.arguments
    if let i = args.firstIndex(of: name), i + 1 < args.count { return args[i + 1] }
    if let fallback { return fallback }
    FileHandle.standardError.write("missing \(name)\n".data(using: .utf8)!)
    exit(2)
}

func log(_ s: String) { print("[recorder] \(s)"); fflush(stdout) }

// --normalize IN OUT: raise quiet game audio to a normal level (video stream copied untouched).
let pcmSettings: [String: Any] = [
    AVFormatIDKey: kAudioFormatLinearPCM, AVLinearPCMBitDepthKey: 32, AVLinearPCMIsFloatKey: true,
    AVLinearPCMIsNonInterleaved: false, AVLinearPCMIsBigEndianKey: false, AVSampleRateKey: 48_000, AVNumberOfChannelsKey: 2,
]

/// Calls body on every float sample of the buffer, in place.
func forEachSample(_ buffer: CMSampleBuffer, _ body: (inout Float) -> Void) {
    guard let block = buffer.dataBuffer else { return }
    let total = CMBlockBufferGetDataLength(block)
    var offset = 0
    while offset < total {
        var length = 0
        var pointer: UnsafeMutablePointer<CChar>?
        guard CMBlockBufferGetDataPointer(block, atOffset: offset, lengthAtOffsetOut: &length, totalLengthOut: nil,
                                          dataPointerOut: &pointer) == kCMBlockBufferNoErr, let pointer, length > 0 else { return }
        pointer.withMemoryRebound(to: Float.self, capacity: length / 4) { f in
            for i in 0..<(length / 4) { body(&f[i]) }
        }
        offset += length
    }
}

func normalize(_ inURL: URL, _ outURL: URL) async throws {
    let asset = AVURLAsset(url: inURL)
    guard let videoTrack = try await asset.loadTracks(withMediaType: .video).first,
          let audioTrack = try await asset.loadTracks(withMediaType: .audio).first else {
        log("normalize: missing a track, leaving file as is"); return
    }

    // Pass 1: loudness histogram, so a single thunder clap doesn't dictate the gain.
    var histogram = [Int](repeating: 0, count: 1000)
    var count = 0
    do {
        let reader = try AVAssetReader(asset: asset)
        let out = AVAssetReaderTrackOutput(track: audioTrack, outputSettings: pcmSettings)
        reader.add(out)
        reader.startReading()
        while let buffer = out.copyNextSampleBuffer() {
            forEachSample(buffer) { s in histogram[min(999, Int(abs(s) * 1000))] += 1; count += 1 }
        }
    }
    var acc = 0, p995: Float = 1
    for (i, c) in histogram.enumerated() {
        acc += c
        if Double(acc) >= Double(count) * 0.995 { p995 = Float(i + 1) / 1000; break }
    }
    let gain = min(8, max(1, 0.7 / p995))
    log(String(format: "normalize: 99.5th percentile level %.3f, applying gain %.2fx", p995, gain))

    // Pass 2: copy video, re-encode audio with gain and a soft limiter.
    try? FileManager.default.removeItem(at: outURL)
    let reader = try AVAssetReader(asset: asset)
    let videoOut = AVAssetReaderTrackOutput(track: videoTrack, outputSettings: nil)
    let audioOut = AVAssetReaderTrackOutput(track: audioTrack, outputSettings: pcmSettings)
    reader.add(videoOut)
    reader.add(audioOut)

    let writer = try AVAssetWriter(outputURL: outURL, fileType: .mp4)
    let videoFormat = try await videoTrack.load(.formatDescriptions).first
    let videoIn = AVAssetWriterInput(mediaType: .video, outputSettings: nil, sourceFormatHint: videoFormat)
    let audioIn = AVAssetWriterInput(mediaType: .audio, outputSettings: [
        AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 48_000, AVNumberOfChannelsKey: 2, AVEncoderBitRateKey: 256_000,
    ])
    writer.add(videoIn)
    writer.add(audioIn)
    reader.startReading()
    writer.startWriting()
    guard let first = videoOut.copyNextSampleBuffer() else { log("normalize: no video"); return }
    writer.startSession(atSourceTime: first.presentationTimeStamp)
    videoIn.append(first)

    let knee: Float = 0.8
    await withTaskGroup(of: Void.self) { group in
        group.addTask {
            await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
                videoIn.requestMediaDataWhenReady(on: DispatchQueue(label: "norm.video")) {
                    while videoIn.isReadyForMoreMediaData {
                        guard let b = videoOut.copyNextSampleBuffer() else { videoIn.markAsFinished(); c.resume(); return }
                        videoIn.append(b)
                    }
                }
            }
        }
        group.addTask {
            await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
                audioIn.requestMediaDataWhenReady(on: DispatchQueue(label: "norm.audio")) {
                    while audioIn.isReadyForMoreMediaData {
                        guard let b = audioOut.copyNextSampleBuffer() else { audioIn.markAsFinished(); c.resume(); return }
                        forEachSample(b) { s in
                            let v = s * gain, a = abs(v)
                            s = a <= knee ? v : (v < 0 ? -1 : 1) * (knee + (1 - knee) * tanh((a - knee) / (1 - knee)))
                        }
                        audioIn.append(b)
                    }
                }
            }
        }
    }
    await writer.finishWriting()
    log("normalize: wrote \(outURL.path) (status \(writer.status.rawValue))")
    if let e = writer.error { log("normalize error: \(e.localizedDescription)") }
}

if let i = CommandLine.arguments.firstIndex(of: "--normalize"), i + 2 < CommandLine.arguments.count {
    try await normalize(URL(fileURLWithPath: CommandLine.arguments[i + 1]), URL(fileURLWithPath: CommandLine.arguments[i + 2]))
    exit(0)
}

let signalDir = URL(fileURLWithPath: arg("--signal-dir"))
let outURL = URL(fileURLWithPath: arg("--out"))
let width = Int(arg("--width", "1920"))!
let height = Int(arg("--height", "1080"))!
let fps = Int32(arg("--fps", "60"))!

final class Recorder: NSObject, SCStreamOutput, SCStreamDelegate {
    private let writer: AVAssetWriter
    private let videoInput: AVAssetWriterInput
    private let audioInput: AVAssetWriterInput
    private var sessionStarted = false
    private var videoFrames = 0
    private var audioBuffers = 0
    private let lock = NSLock()
    private var finished = false

    init(url: URL) throws {
        try? FileManager.default.removeItem(at: url)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        writer = try AVAssetWriter(outputURL: url, fileType: .mp4)

        videoInput = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: width,
            AVVideoHeightKey: height,
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: 20_000_000,
                AVVideoExpectedSourceFrameRateKey: fps,
                AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel,
            ],
        ])
        videoInput.expectsMediaDataInRealTime = true

        audioInput = AVAssetWriterInput(mediaType: .audio, outputSettings: [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: 48_000,
            AVNumberOfChannelsKey: 2,
            AVEncoderBitRateKey: 256_000,
        ])
        audioInput.expectsMediaDataInRealTime = true

        writer.add(videoInput)
        writer.add(audioInput)
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer buffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard buffer.isValid else { return }
        lock.lock(); defer { lock.unlock() }
        if finished { return }

        switch type {
        case .screen:
            // Skip idle/blank frames; only complete frames carry an image.
            guard let attachments = CMSampleBufferGetSampleAttachmentsArray(buffer, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]],
                  let raw = attachments.first?[.status] as? Int,
                  let status = SCFrameStatus(rawValue: raw), status == .complete else { return }
            if !sessionStarted {
                writer.startWriting()
                writer.startSession(atSourceTime: buffer.presentationTimeStamp)
                sessionStarted = true
                log("recording started")
            }
            if videoInput.isReadyForMoreMediaData, videoInput.append(buffer) { videoFrames += 1 }
        case .audio:
            guard sessionStarted else { return }
            if audioInput.isReadyForMoreMediaData, audioInput.append(buffer) { audioBuffers += 1 }
        default:
            break
        }
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        log("stream stopped with error: \(error.localizedDescription)")
    }

    func finish() async {
        lock.lock()
        finished = true
        let started = sessionStarted
        lock.unlock()
        guard started else { log("no frames were captured"); return }
        videoInput.markAsFinished()
        audioInput.markAsFinished()
        await writer.finishWriting()
        log("wrote \(outURL.path) (\(videoFrames) video frames, \(audioBuffers) audio buffers, status \(writer.status.rawValue))")
        if let error = writer.error { log("writer error: \(error.localizedDescription)") }
    }
}

func fileExists(_ name: String) -> Bool {
    FileManager.default.fileExists(atPath: signalDir.appendingPathComponent(name).path)
}

func processAlive(_ pid: pid_t) -> Bool { kill(pid, 0) == 0 }

var stopRequested = false
signal(SIGINT) { _ in stopRequested = true }
signal(SIGTERM) { _ in stopRequested = true }

// Check permission up front so the user gets a clear message before the game even starts.
do {
    _ = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
} catch {
    log("Screen recording permission is needed. Grant it to the app running this script in System Settings > Privacy & Security > Screen & System Audio Recording, then run it again.")
    log("(\(error.localizedDescription))")
    exit(3)
}

log("waiting for the game to signal start...")
while !fileExists("start") {
    if stopRequested || fileExists("stop") { log("stopped before start"); exit(1) }
    try await Task.sleep(nanoseconds: 100_000_000)
}
let pidText = (try? String(contentsOf: signalDir.appendingPathComponent("start"), encoding: .utf8)) ?? ""
guard let pid = pid_t(pidText.trimmingCharacters(in: .whitespacesAndNewlines)) else {
    log("start signal did not contain a process ID"); exit(1)
}

// Bring the game to the front: background windows can be shrunk/transformed by the window
// manager (e.g. Stage Manager), which breaks display lookup and crops.
NSRunningApplication(processIdentifier: pid)?.activate()
try await Task.sleep(nanoseconds: 1_500_000_000)

var resolved: (SCShareableContent, SCWindow, SCDisplay)?
for attempt in 1...20 {
    let c = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
    let windows = c.windows.filter { $0.owningApplication?.processID == pid && $0.frame.width > 200 }
    if let w = windows.max(by: { $0.frame.width * $0.frame.height < $1.frame.width * $1.frame.height }),
       let d = c.displays.first(where: { $0.frame.contains(w.frame) }) ?? c.displays.first(where: { $0.frame.intersects(w.frame) }) {
        resolved = (c, w, d)
        break
    }
    log("waiting for the game window to settle (attempt \(attempt))")
    try await Task.sleep(nanoseconds: 500_000_000)
}
guard let (content, window, display) = resolved else {
    log("could not find the game window on any display"); exit(1)
}
log("capturing window '\(window.title ?? "?")' \(Int(window.frame.width))x\(Int(window.frame.height))")

// Capture the display, filtered to just this app (which also limits audio to the game), cropped
// to the window. (SCContentFilter(desktopIndependentWindow:) asserts inside SkyLight on some setups.)
_ = content
guard let app = window.owningApplication else { log("game window has no owning app"); exit(1) }
let filter = SCContentFilter(display: display, including: [app], exceptingWindows: [])
var rect = window.frame.intersection(display.frame)
// Drop the title bar: the game's content area is 16:9, anything above that is window chrome.
let chrome = rect.height - rect.width * 9.0 / 16.0
if chrome > 0 && chrome < 80 {
    rect.origin.y += chrome
    rect.size.height -= chrome
}
let config = SCStreamConfiguration()
config.sourceRect = CGRect(x: rect.minX - display.frame.minX, y: rect.minY - display.frame.minY,
                           width: rect.width, height: rect.height)
log("display \(display.displayID), crop \(Int(rect.width))x\(Int(rect.height)) at \(Int(config.sourceRect.minX)),\(Int(config.sourceRect.minY))")
config.width = width
config.height = height
config.minimumFrameInterval = CMTime(value: 1, timescale: fps)
config.pixelFormat = kCVPixelFormatType_32BGRA
config.showsCursor = false
config.queueDepth = 8
config.capturesAudio = true          // system audio from the captured app, not the microphone
config.excludesCurrentProcessAudio = true
config.sampleRate = 48_000
config.channelCount = 2
if #available(macOS 14.0, *) { config.scalesToFit = true }

let recorder = try Recorder(url: outURL)
let stream = SCStream(filter: filter, configuration: config, delegate: recorder)
let queue = DispatchQueue(label: "recorder.samples")
try stream.addStreamOutput(recorder, type: .screen, sampleHandlerQueue: queue)
try stream.addStreamOutput(recorder, type: .audio, sampleHandlerQueue: queue)
try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
    stream.startCapture { error in if let error { c.resume(throwing: error) } else { c.resume() } }
}

let deadline = Date().addingTimeInterval(15 * 60)
while !stopRequested && !fileExists("stop") && processAlive(pid) && Date() < deadline {
    try await Task.sleep(nanoseconds: 50_000_000)
}
await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
    stream.stopCapture { _ in c.resume() }
}
await recorder.finish()
